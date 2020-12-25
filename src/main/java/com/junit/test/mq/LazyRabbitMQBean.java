package com.junit.test.mq;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitMessagingTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.junit.test.TestUtil;
import com.rabbitmq.client.ConnectionFactory;
/**
 * 构建rabbit应用
 * @author jubin.zhang
 *
 */
public class LazyRabbitMQBean extends LazyMQBean{
	@Override
	public Object buildBeanProcess(Class classBean) throws InstantiationException, IllegalAccessException {
		if(classBean == AmqpAdmin.class) {
			if(admin == null) {
				if(template == null) {
					buildRabbitTemplate();
				}
				admin = new RabbitAdmin(template.getConnectionFactory());
			}
			return admin;
		}else if(classBean == RabbitMessagingTemplate.class) {
			RabbitMessagingTemplate objM = (RabbitMessagingTemplate) classBean.newInstance();
			if(template == null) {
				buildRabbitTemplate();
			}
			objM.setRabbitTemplate(template);
			return objM;
		}else if(classBean == RabbitTemplate.class) {
			if(template == null) {
				buildRabbitTemplate();
			}
			return template;
		}
		return null;
	}
	private RabbitTemplate template = null;
	private void buildRabbitTemplate() throws InstantiationException, IllegalAccessException {
		this.template = RabbitTemplate.class.newInstance();
		// 定义一个连接工厂
		ConnectionFactory factory = new ConnectionFactory();
		// 设置服务端地址（域名地址/ip）
		factory.setHost(TestUtil.getPropertiesValue("spring.rabbitmq.host"));
		// 设置服务器端口号
		factory.setPort(Integer.valueOf(TestUtil.getPropertiesValue("spring.rabbitmq.port","5672")));
		// 设置虚拟主机(相当于数据库中的库)
		factory.setVirtualHost("/");
		// 设置用户名
		factory.setUsername(TestUtil.getPropertiesValue("spring.rabbitmq.username"));
		// 设置密码
		factory.setPassword(TestUtil.getPropertiesValue("spring.rabbitmq.password"));
//		Connection connection = factory.newConnection();
		template.setConnectionFactory(new CachingConnectionFactory(factory));
	}
}