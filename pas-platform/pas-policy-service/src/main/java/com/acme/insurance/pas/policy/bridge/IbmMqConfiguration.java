package com.acme.insurance.pas.policy.bridge;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import jakarta.jms.JMSException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.core.JmsTemplate;

@Configuration
@ConditionalOnExpression("'${pas.bridge.mq.host:}'.trim().length() > 0")
public class IbmMqConfiguration {
  @Bean
  MQConnectionFactory mqConnectionFactory(
      @Value("${pas.bridge.mq.host}") String host,
      @Value("${pas.bridge.mq.port:1414}") int port,
      @Value("${pas.bridge.mq.queue-manager}") String queueManager,
      @Value("${pas.bridge.mq.channel}") String channel,
      @Value("${pas.bridge.mq.tls-cipher-suite:}") String cipherSuite,
      @Value("${PAS_BRIDGE_MQ_USER:}") String user,
      @Value("${PAS_BRIDGE_MQ_PASSWORD:}") String password)
      throws JMSException {
    MQConnectionFactory factory = new MQConnectionFactory();
    factory.setHostName(host);
    factory.setPort(port);
    factory.setQueueManager(queueManager);
    factory.setChannel(channel);
    factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
    if (!cipherSuite.isBlank()) {
      factory.setSSLCipherSuite(cipherSuite);
    }
    if (!user.isBlank()) {
      factory.setStringProperty(WMQConstants.USERID, user);
      factory.setStringProperty(WMQConstants.PASSWORD, password);
    }
    return factory;
  }

  @Bean
  JmsTemplate jmsTemplate(MQConnectionFactory factory) {
    JmsTemplate template = new JmsTemplate(factory);
    template.setExplicitQosEnabled(true);
    template.setDeliveryPersistent(true);
    template.setPubSubDomain(false);
    return template;
  }

  @Bean
  LegacyQueuePort legacyQueuePort(JmsTemplate template) {
    return new JmsLegacyQueueAdapter(template);
  }
}
