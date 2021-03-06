package com.github.jklasd.test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import com.github.jklasd.test.dubbo.LazyDubboBean;
import com.github.jklasd.test.spring.LazyConfigurationPropertiesBindingPostProcessor;
import com.github.jklasd.test.spring.ObjectProviderImpl;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import lombok.extern.slf4j.Slf4j;

/**
 * 
 * @author jubin.zhang
 * 2020-11-19
 */
@Slf4j
public class LazyBean {
	public static Map<Class, Object> singleton = Maps.newHashMap();
	public static Map<String, Object> singletonName = Maps.newHashMap();
	/**
	 * 
	 * 构建代理对象 classBean 
	 * @param classBean 代理类型
	 * @param classGeneric 代理类的泛型类型
	 * @return 代理对象
	 */
	public static Object buildProxyForGeneric(Class classBean,Type[] classGeneric) {
		Object tagObject = null;
		if (classBean.isInterface()) {
			InvocationHandler handler = new LazyImple(classBean,null,classGeneric);
			tagObject = Proxy.newProxyInstance(handler.getClass().getClassLoader(), new Class[] { classBean }, handler);
		}
		return tagObject;
	}
	/**
	 * 
	 * 构建代理对象
	 * @param classBean 需要代理的类型
	 * @param beanName 对象Name
	 * @return 代理对象
	 */
	public static Object buildProxy(Class classBean,String beanName) {
		/**
		 * 取缓存值
		 */
		if(singletonName.containsKey(beanName)) {
			return singletonName.get(beanName);
		}
		if(StringUtils.isBlank(beanName)) {
			if (singleton.containsKey(classBean)) {
				return singleton.get(classBean);
			}
		}
		/**
		 * 开始构建对象
		 */
		
		Object tag = null;
		/**
		 * 构建其他类型的代理对象
		 * 【Interface类型】 构建 InvocationHandler 代理对象，JDK自带
		 * 【Class类型】构建MethodInterceptor代理对象，Cglib jar
		 */
		try {
			if (classBean.isInterface()) {
				InvocationHandler handler = new LazyImple(classBean,beanName);
				tag = Proxy.newProxyInstance(handler.getClass().getClassLoader(), new Class[] { classBean }, handler);
			} else {
				Constructor[] structors = classBean.getConstructors();
				/**
				 * 查看是否存在无参构造函数
				 */
				for(Constructor c : structors) {
					if(c.getParameterCount() == 0 ) {
						MethodInterceptor handler = new LazyCglib(classBean,beanName);
						tag = Enhancer.create(classBean, handler);
						break;
					}
				}
				if(tag == null && structors.length>0) {
//					log.warn("不存在无参构造函数");
					LazyCglib handler = new LazyCglib(classBean,beanName,true);
					Enhancer enhancer = new Enhancer();
					enhancer.setSuperclass(classBean);
					enhancer.setCallback(handler);
					tag = enhancer.create(handler.getArgumentTypes(), handler.getArguments());
				}
			}
		} catch (Exception e) {
			if(e.getCause()!=null) {
				log.error("构建代理类异常=>{}",e.getCause().getMessage());
			}else {
				log.error("构建代理类异常=>{}",e.getMessage());
			}
		}
		if(tag!=null) {
			if(StringUtils.isNotBlank(beanName)) {
				singletonName.put(beanName, tag);
			}else {
				singleton.put(classBean, tag);
			}
		}
		return tag;
	}
	/**
	 * 构建代理对象
	 * @param classBean 需要代理的类型
	 * @return 返回ProxyObject
	 */
	public static Object buildProxy(Class classBean) {
		if (singleton.containsKey(classBean)) {
			return singleton.get(classBean);
		}
		Object tag = buildProxy(classBean,null);
		if(tag!=null) {
			singleton.put(classBean, tag);
		}
		return tag;
	}
	public static void setObj(Field f,Object obj,Object proxyObj) {
		setObj(f, obj, proxyObj, null);
	}
	/**
	 * 反射写入值。
	 * @param f	属性
	 * @param obj	属性所属目标对象
	 * @param proxyObj 写入属性的代理对象
	 * @param proxyBeanName 存在bean名称时，可传入。
	 * 
	 * 
	 */
	public static void setObj(Field f,Object obj,Object proxyObj,String proxyBeanName) {
		if(proxyObj == null) {//延迟注入,可能启动时，未加载到bean
//			TestUtil.loadLazyAttr(obj, f, proxyBeanName);
		}
		try {
			if (!f.isAccessible()) {
				f.setAccessible(true);
			}
			f.set(obj, proxyObj);
		} catch (Exception e) {
			log.error("注入对象异常",e);
		}
	}
	/**
	 * 注入
	 * @param obj
	 * @param objClassOrSuper
	 */
	static Set<String> exist = Sets.newHashSet();
	/**
	 * 注入对应的属性值
	 * 
	 * 查询相应的属性注解
	 * 【Autowired】进行注入 相应类的代理对象
	 * 【Resource】进行注入 相应类的代理对象
	 * 【Value】注入相应的配置值。
	 * 
	 * @param obj 目标对象
	 * @param objClassOrSuper 目标对象父类，用于递归注入。
	 */
	@SuppressWarnings("unchecked")
	public static void processAttr(Object obj, Class objClassOrSuper) {
//		if(objClassOrSuper.getName().contains("ProductRuleConfiguration")) {
//			log.info("需要注入=>{}=>{}",objClassOrSuper.getName());
//		}
		if(exist.contains(obj.hashCode()+"="+objClassOrSuper.getName())) {
			return;
		}
		exist.add(obj.hashCode()+"="+objClassOrSuper.getName());
		Field[] fields = objClassOrSuper.getDeclaredFields();
		for(Field f : fields){
			Autowired aw = f.getAnnotation(Autowired.class);
			if (aw != null) {
				String bName = f.getAnnotation(Qualifier.class)!=null?f.getAnnotation(Qualifier.class).value():null;
				if(f.getType() == List.class) {
					ParameterizedType t = (ParameterizedType) f.getGenericType();
					Type[] item = t.getActualTypeArguments();
					if(item.length == 1) {
						//处理一个集合注入
						try {
							Class<?> c = Class.forName(item[0].getTypeName());
							setObj(f, obj, findListBean(c));
							log.info("注入集合=>{}",f.getName());
						} catch (ClassNotFoundException e) {
							e.printStackTrace();
						}
					}else {
						log.info("其他特殊情况");
					}
				}else {
					if(LazyBean.existBean(f.getType()) && TestUtil.getExistBean(f.getType(), f.getName())!=null) {
						setObj(f, obj, TestUtil.getExistBean(f.getType(), f.getName()));
					}else {
						setObj(f, obj, buildProxy(f.getType(),bName));
					}
				}
			} else {
				Value v = f.getAnnotation(Value.class);
				if (v != null) {
					setObj(f, obj, TestUtil.value(v.value().replace("${", "").replace("}", ""), f.getType()));
				} else {
					javax.annotation.Resource c = f.getAnnotation(javax.annotation.Resource.class);
					if (c != null) {
						if(StringUtils.isNotBlank(c.name())) {
							setObj(f, obj, buildProxy(f.getType(),c.name()),c.name());
						}else {
							setObj(f, obj, buildProxy(f.getType()));
						}
					} else {
						log.debug("不需要需要注入=>{}", f.getName());
					}
				}
			}
		}
		Class<?> superC = objClassOrSuper.getSuperclass();
		if (superC != null) {
			processAttr(obj, superC);
		}

		Method[] ms = obj.getClass().getDeclaredMethods();
		postConstruct(obj, ms,superC);
		
		ConfigurationProperties proconfig = (ConfigurationProperties) objClassOrSuper.getAnnotation(ConfigurationProperties.class);
		if(proconfig!=null) {
			LazyConfigurationPropertiesBindingPostProcessor.processConfigurationProperties(obj,proconfig);
		}
	}

	public static Object processStatic(Class c) {
		try {
			Object obj = buildProxy(c);
			processAttr(obj, c);
			return obj;
		} catch (Exception e) {
			log.error("处理静态工具类异常=>{}",c);
			return null;
		}
	}
	/**
	 * 对目标对象方法进行处理
	 * @param obj 目标对象
	 * @param ms 方法组
	 * @param sup 父类
	 * 
	 * 主要处理 
	 * 【1】PostConstruct注解方法
	 * 【2】setApplicationContext
	 * 
	 * 当目标对象存在父类时，遍历所有父类对相应方法进行处理
	 */
	private static void postConstruct(Object obj, Method[] ms,Class sup) {
		if(sup != null) {
			ms = sup.getDeclaredMethods();
			postConstruct(obj, ms, sup.getSuperclass());
		}
		for (Method m : ms) {
			if (m.getAnnotation(PostConstruct.class) != null) {//当实际对象存在初始化方法时。
				try {
					if (!m.isAccessible()) {
						m.setAccessible(true);
					}
					m.invoke(obj, null);
				} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
					log.error("初始化方法执行异常{}#{}",obj,m);
					log.error("初始化方法执行异常",e);
				}
			}else if(m.getName().equals("setApplicationContext")//当对象方法存是setApplicationContext
					&& (sup == null || !sup.getName().contains("AbstractJUnit4SpringContextTests"))) {
				try {
					if(m!=null) {
						try {
							m.invoke(obj,TestUtil.getApplicationContext());
						} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
							log.error("不能注入applicationContext",e);
						}
					}
				} catch (SecurityException e) {
				}
			}
		}
	}
	@SuppressWarnings("unchecked")
	public static boolean setAttr(String field, Object obj,Class<?> superClass,Object value) {
			Object fv = value;
			String mName = "set"+field.substring(0, 1).toUpperCase()+field.substring(1);
			Method[] methods = superClass.getDeclaredMethods();
			for(Method m : methods) {
				if(Objects.equal(m.getName(), mName)) {
					if(value instanceof String) {
						fv = TestUtil.value(value.toString(), m.getParameterTypes()[0]);	
					}
					try {
						m.invoke(obj, fv);
					} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
						e.printStackTrace();
					}
					return true;
				}
			}
			Field[] fields = superClass.getDeclaredFields();
			boolean found = false;
				for(Field f : fields){
					if(Objects.equal(f.getName(), field)) {
						if(value instanceof String) {
							fv = TestUtil.value(value.toString(), f.getType());	
						}
						try {
							setObj(f, obj, fv);
						} catch (IllegalArgumentException e) {
							log.error("",e);
							return false;
						}
						return true;
					}
				}
			Class<?> superC = superClass.getSuperclass();
			if (!found && superC != null ) {
				return setAttr(field,obj,superC,value);
			}
		return false;
	}
	
	public static boolean existBean(Class beanClass) {
		Annotation[] anns = beanClass.getDeclaredAnnotations();
		for(Annotation ann : anns) {
			Class<?> type = ann.annotationType();
			if ((type == Component.class || type.getAnnotation(Component.class)!=null)
					|| (type == Service.class || type.getAnnotation(Service.class)!=null)
					|| (type == Configuration.class || type.getAnnotation(Configuration.class)!=null)
					|| (type == RestController.class || type.getAnnotation(RestController.class)!=null)
					|| (type == Controller.class || type.getAnnotation(Controller.class)!=null)
					) {
				return true;
			}
		}
		return false;
	}
	/**
	 * 
	 * @param beanClass 目标类
	 * @param tagAnn 获取Annotation 
	 * @return 返回存在的 Annotation
	 */
	public static Annotation findByAnnotation(Class beanClass,Class<? extends Annotation> tagAnn) {
		Annotation[] anns = beanClass.getDeclaredAnnotations();
		for(Annotation ann : anns) {
			Class<?> type = ann.annotationType();
			if ((type == tagAnn || type.getAnnotation(tagAnn)!=null)) {
				return ann;
			}
		}
		return null;
	}
	
	public static Map<String,Object> beanMaps = Maps.newHashMap();
	/**
	 * 通过BeanName 获取bean
	 * @param beanName beanName
	 * @return 返回bean
	 */
	public static Object findBean(String beanName) {
		if(beanMaps.containsKey(beanName)) {
			return beanMaps.get(beanName);
		}
		Object bean = null;
		Class tag = ScanUtil.findClassByName(beanName);
		if(tag == null) {
			tag = ScanUtil.findClassByClassName(beanName.substring(0, 1).toUpperCase()+beanName.substring(1));
		}
		if (tag != null) {
			if(LazyDubboBean.isDubbo(tag)) {
				return LazyDubboBean.buildBean(tag);
			}else {
				bean = LazyBean.buildProxy(tag);
			}
			beanMaps.put(beanName, bean);
		}
		return bean;
	}
	
	/**
	 * 
	 * @param beanName bean名称
	 * @param type bean类型
	 * @return 返回bean对象
	 */
	public static Object findBean(String beanName,Class<?> type) {
		if(type.isInterface()) {
			List<Class> classList = ScanUtil.findClassImplInterface(type);
			for(Class c : classList) {
				Service ann = (Service) findByAnnotation(c,Service.class);
				Component cAnn = (Component) findByAnnotation(c,Component.class);
				if ((ann != null && ann.value().equals(beanName)) | (cAnn != null && cAnn.value().equals(beanName))) {
					return buildProxy(c, beanName);
				}
			}
			log.warn("ScanUtil # findBean=>Interface[{}]",type);
		}else if(Modifier.isAbstract(type.getModifiers())) {//抽象类
		}else {
			Object obj = findBean(beanName); 
			try {
				if(type.getConstructors().length>0) {
					return  obj == null?type.newInstance():obj;
				}else {
					throw new NoSuchBeanDefinitionException("没有获取到构造器");
				}
			} catch (InstantiationException | IllegalAccessException e) {
				log.error("不能构建bean=>{}=>{}",beanName,type);
			}
		}
		return null;
	}
	
	/**
	 * 
	 * 通过注解查找Bean
	 * 
	 * @param annotationType Annotation类型
	 * @return 返回存在annotationType 的对象
	 */
	public static Map<String, Object> findBeanWithAnnotation(Class<? extends Annotation> annotationType) {
		List<Class<?>> list = ScanUtil.findClassWithAnnotation(annotationType);
		Map<String, Object> annoClass = Maps.newHashMap();
		list.stream().forEach(c ->{
//			String beanName = getBeanName(c);
			annoClass.put(c.getSimpleName(), LazyBean.buildProxy(c));
		});
		return annoClass;
	}
	
	public static Object findBeanByInterface(Class interfaceClass, Type[] classGeneric) {
		if(classGeneric == null) {
			return findBeanByInterface(interfaceClass);
		}
		if(interfaceClass.getName().startsWith(ScanUtil.SPRING_PACKAGE)) {
			List<Class> cL = ScanUtil.findClassImplInterface(interfaceClass,ScanUtil.findClassMap(ScanUtil.SPRING_PACKAGE),null);
			if(!cL.isEmpty()) {
				Class c = cL.get(0);
			}else {
				if(interfaceClass == ObjectProvider.class) {
					return new ObjectProviderImpl(classGeneric[0]);
				}
			}
		}
		return null;
	}
	
	/**
	 * 扫描类 for bean
	 * @param interfaceClass  接口
	 * @return 返回实现接口的对象
	 */
	public static Object findBeanByInterface(Class interfaceClass) {
		if(interfaceClass == ApplicationContext.class || ScanUtil.isExtends(ApplicationContext.class, interfaceClass)
				|| ScanUtil.isExtends(interfaceClass,ApplicationContext.class)) {
			return TestUtil.getApplicationContext();
		}
		if(interfaceClass == Environment.class
				|| ScanUtil.isExtends(Environment.class,interfaceClass)
				|| ScanUtil.isExtends(interfaceClass, Environment.class)) {
			return TestUtil.getApplicationContext().getEnvironment();
		}
		if(interfaceClass.getPackage().getName().startsWith(ScanUtil.SPRING_PACKAGE)) {
			List<Class> cL = ScanUtil.findClassImplInterface(interfaceClass,ScanUtil.findClassMap(ScanUtil.SPRING_PACKAGE),null);
			if(!cL.isEmpty()) {
				return LazyBean.buildProxy(cL.get(0));
			}
		}
		List<Class> tags = ScanUtil.findClassImplInterface(interfaceClass);
		if (!tags.isEmpty()) {
			return LazyBean.buildProxy(tags.get(0));
		}
		return null;
	}
	
	/**
	 * 通过class 获取 bean
	 * @param requiredType bean类型
	 * @return 返回bean
	 */
	public static Object findBean(Class<?> requiredType) {
		if(LazyDubboBean.isDubbo(requiredType)) {
			return LazyDubboBean.buildBean(requiredType);
		}
		if(requiredType.getName().startsWith("org.springframework")) {
			return LazyBean.buildProxy(requiredType);
		}
		if(requiredType.isInterface()) {
			List<Class> tag = ScanUtil.findClassImplInterface(requiredType);
			if (!tag.isEmpty()) {
				return LazyBean.buildProxy(tag.get(0));
			}
		}else if(ScanUtil.isInScanPath(requiredType)){
			return LazyBean.buildProxy(requiredType);
		}
		return null;
	}
	
	/**
	 * 通过class 查找它的所有继承者或实现者
	 * @param requiredType 接口或者抽象类
	 * @return 放回List对象
	 */
	@SuppressWarnings("unchecked")
	public static List findListBean(Class<?> requiredType) {
		List list = Lists.newArrayList();
		List<Class> tags = null;
		if(requiredType.isInterface()) {
			tags = ScanUtil.findClassImplInterface(requiredType);
		}else {
			tags = ScanUtil.findClassExtendAbstract(requiredType);
		}
		if (!tags.isEmpty()) {
			tags.stream().forEach(item ->list.add(LazyBean.buildProxy(item)));
		}
		return list;
	}
}

