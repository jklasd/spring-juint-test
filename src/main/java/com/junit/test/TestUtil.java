package com.junit.test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import lombok.extern.slf4j.Slf4j;

/**
 * @author jubin.zhang
 *	2020-11-19
 *
 */
@Slf4j
@Component
@SuppressWarnings("rawtypes")
public class TestUtil implements ApplicationContextAware,BeanPostProcessor{
	private static boolean test;
	public static String mapperPath = "classpath*:/mapper/**/*.xml";
	public static String mapperScanPath = "com.mapper";
	public TestUtil() {
		log.info("实例化TestUtil");
	}
	public static PooledDataSource dataSource;
	private static Map<String,Object> lazyBeanObjMap;
	private static Map<String,Field> lazyBeanFieldMap;
	private static Map<String,String> lazyBeanNameMap;
	
	static void loadLazyAttr(Object obj,Field f,String beanName) {
		if(lazyBeanObjMap == null) {
			lazyBeanObjMap = Maps.newHashMap();
			lazyBeanFieldMap = Maps.newHashMap();
			lazyBeanNameMap = Maps.newHashMap();
		}
		String fKey = obj.getClass().getName()+"_"+f.getName();
		lazyBeanObjMap.put(fKey,obj);
		lazyBeanFieldMap.put(fKey,f);
		lazyBeanNameMap.put(fKey, beanName);
	}
	
	private static ApplicationContext staticApplicationContext;
	
	public static void buildDataSource(String url,String username,String passwd) {
		if(dataSource == null) {
			dataSource = new PooledDataSource();
		}
		dataSource.setUrl(TestUtil.getPropertiesValue("jdbc.url"));
		dataSource.setUsername(TestUtil.getPropertiesValue("jdbc.username"));
		dataSource.setPassword(TestUtil.getPropertiesValue("jdbc.password"));
		dataSource.setDriver(TestUtil.getPropertiesValue("jdbc.driver",""));
	}
	
	public static boolean isTest() {
		return test;
	}
	public static void openTest() {
		test = true;
	}
	public void setApplicationContextLocal(ApplicationContext applicationContext) {
		staticApplicationContext = applicationContext;
	}
	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		staticApplicationContext = applicationContext;
		if(test) {
			DefaultListableBeanFactory bf = (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
			Object bean = bf.getSingleton("org.springframework.context.annotation.internalAutowiredAnnotationProcessor");
			if(bean != null) {
				((AutowiredAnnotationBeanPostProcessor)bean).setRequiredParameterValue(false);
			}else {
				log.info("初始化失败TestUtil");
			}
			ScanUtil.loadAllClass();
			processConfig();
		}
		
		lazyProcessAttr();
	}
	private void lazyProcessAttr() {
		if(lazyBeanObjMap!=null) {
			lazyBeanObjMap.keySet().forEach(fKey->{
				Object obj = lazyBeanObjMap.get(fKey);
				Field attr = lazyBeanFieldMap.get(fKey);
				try {
					attr.set(obj, LazyBean.buildProxy(attr.getType(),lazyBeanNameMap.get(fKey)));
				} catch (IllegalArgumentException | IllegalAccessException e) {
					e.printStackTrace();
				}
			});
		}
	}
	private void processConfig() {
		
		List<Class> list = ScanUtil.findStaticMethodClass();
		log.info("static class =>{}",list.size());
//		String key = "redis.node1.port";
//		log.info("{}=>{}",key,getPropertiesValue(key));
		/**
		 * 不能是抽象类
		 */
		list.stream().filter(classItem -> classItem != getClass() && !Modifier.isAbstract(classItem.getModifiers())).forEach(classItem->{
			log.info("static class =>{}",classItem);
			LazyBean.processStatic(classItem);
		});
	}
	
	public static Object getExistBean(Class<?> classD) {
		if(classD == ApplicationContext.class) {
			return bf;
		}
		Object obj = staticApplicationContext.getBean(classD);
		return obj;
	}
	
	@SuppressWarnings("unchecked")
	public static Object buildBean( Class c) {
		Object obj = null;
		try {
			obj = bf.getBean(c);
			if(obj!=null) {
				return obj;
			}
		} catch (Exception e) {
			log.error("不存在");
		}
		obj = staticApplicationContext.getAutowireCapableBeanFactory().createBean(c);
		return obj; 
	}
	
	public static void registerBean(Object bean) {
		DefaultListableBeanFactory bf = (DefaultListableBeanFactory) staticApplicationContext.getAutowireCapableBeanFactory();
		Object obj = null;
		try {
			obj = bf.getBean(bean.getClass());
		} catch (Exception e) {
			log.error("不存在");
		}
		if(obj==null) {
			bf.registerSingleton(bean.getClass().getPackage().getName()+"."+bean.getClass().getSimpleName(), bean);
		}
	}
	
	@SuppressWarnings("unchecked")
	public static Object getExistBean(Class classD,String beanName) {
		try {
			if(classD == ApplicationContext.class) {
				return bf;
			}
			Object obj = staticApplicationContext.getBean(classD);
			return obj;
		}catch(NullPointerException e) {
			return null;
		}catch (NoUniqueBeanDefinitionException e) {
			if(beanName != null) {
				Object obj = staticApplicationContext.getBean(beanName);
				return obj;
			}
			return null;
		}catch (NoSuchBeanDefinitionException e) {
			return null;
		}catch(UnsatisfiedDependencyException e) {
			log.error("UnsatisfiedDependencyException=>{},{}获取异常",classD,beanName);
			return null;
		}
	}
	public static String getPropertiesValue(String key,String defaultStr) {
		if(staticApplicationContext!=null) {
			String[] keys = key.split(":");
			String value = staticApplicationContext.getEnvironment().getProperty(keys[0]);
			if(StringUtils.isNotBlank(value)) {
				return value;
			}else {
				return keys.length>1?keys[1]:defaultStr;
			}
		}
		return "";
	}
	public static String getPropertiesValue(String key) {
		return getPropertiesValue(key,null);
	}
	public static Object value(String key,Class type) {
		String value = getPropertiesValue(key);
		try {
			if(type == null || type == String.class) {
				return	value;
			}else if(type == Integer.class || type == int.class) {
				return Integer.valueOf(value);
			}else if(type == Long.class || type == long.class) {
				return Long.valueOf(value);
			}else if(type == Double.class || type == double.class) {
				return Double.valueOf(value);
			}else if(type == BigDecimal.class) {
				return new BigDecimal(value);
			}else if(type == Boolean.class || type == boolean.class) {
				return new Boolean(value);
			}else {
				log.info("其他类型");
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		return null;
	}
	
	static List<String> xmlList;
	public static void loadXml(String... xmls) {
		xmlList = Lists.newArrayList(xmls);
	}
	static BeanFactory bf = new BeanFactory();
	public static String dubboXml = "classpath*:/dubbo-context.xml";
	public static String mapperJdbcPrefix = "";
	
	static class BeanFactory implements ApplicationContext{
		@Override
		public Environment getEnvironment() {
			return staticApplicationContext.getEnvironment();
		}

		@Override
		public boolean containsBeanDefinition(String beanName) {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public int getBeanDefinitionCount() {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public String[] getBeanDefinitionNames() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public String[] getBeanNamesForType(ResolvableType type) {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public String[] getBeanNamesForType(Class<?> type) {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public String[] getBeanNamesForType(Class<?> type, boolean includeNonSingletons, boolean allowEagerInit) {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public <T> Map<String, T> getBeansOfType(Class<T> type) throws BeansException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public <T> Map<String, T> getBeansOfType(Class<T> type, boolean includeNonSingletons, boolean allowEagerInit)
				throws BeansException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public String[] getBeanNamesForAnnotation(Class<? extends Annotation> annotationType) {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType)
				throws BeansException {
			return ScanUtil.findBeanWithAnnotation(annotationType);
		}

		@Override
		public <A extends Annotation> A findAnnotationOnBean(String beanName, Class<A> annotationType)
				throws NoSuchBeanDefinitionException {
			return null;
		}

		@Override
		public Object getBean(String name) throws BeansException {
			if(staticApplicationContext.containsBean(name)) {
				return staticApplicationContext.getBean(name);
			}else {
				return ScanUtil.findBean(name);
			}
		}

		@Override
		public <T> T getBean(String name, Class<T> requiredType) throws BeansException {
			return null;
		}

		@Override
		public <T> T getBean(Class<T> requiredType) throws BeansException {
			try {
				Object bean = staticApplicationContext.getBean(requiredType);
				return (T) bean;
			} catch (NoSuchBeanDefinitionException e) {
				return (T)ScanUtil.findBean(requiredType);
			}
		}

		@Override
		public Object getBean(String name, Object... args) throws BeansException {
			return staticApplicationContext.getBean(name, args);
		}

		@Override
		public <T> T getBean(Class<T> requiredType, Object... args) throws BeansException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public boolean containsBean(String name) {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public boolean isSingleton(String name) throws NoSuchBeanDefinitionException {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public boolean isPrototype(String name) throws NoSuchBeanDefinitionException {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public boolean isTypeMatch(String name, ResolvableType typeToMatch) throws NoSuchBeanDefinitionException {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public boolean isTypeMatch(String name, Class<?> typeToMatch) throws NoSuchBeanDefinitionException {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public Class<?> getType(String name) throws NoSuchBeanDefinitionException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public String[] getAliases(String name) {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public org.springframework.beans.factory.BeanFactory getParentBeanFactory() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public boolean containsLocalBean(String name) {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public String getMessage(String code, Object[] args, Locale locale) throws NoSuchMessageException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public String getMessage(MessageSourceResolvable resolvable, Locale locale) throws NoSuchMessageException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public void publishEvent(ApplicationEvent event) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void publishEvent(Object event) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public Resource[] getResources(String locationPattern) throws IOException {
			return ScanUtil.getResources(locationPattern);
		}

		@Override
		public Resource getResource(String location) {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public ClassLoader getClassLoader() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public String getId() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public String getApplicationName() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public String getDisplayName() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public long getStartupDate() {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public ApplicationContext getParent() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public AutowireCapableBeanFactory getAutowireCapableBeanFactory() throws IllegalStateException {
			// TODO Auto-generated method stub
			return null;
		}
		
	}

	public static PropertySources getPropertySource() {
		StandardEnvironment env = (StandardEnvironment) staticApplicationContext.getEnvironment();
		return env.getPropertySources();
	}

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		// TODO Auto-generated method stub
		return bean;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		// TODO Auto-generated method stub
		return bean;
	}

}
