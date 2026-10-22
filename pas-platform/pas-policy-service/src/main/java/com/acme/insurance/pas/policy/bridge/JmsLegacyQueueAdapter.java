package com.acme.insurance.pas.policy.bridge;

import com.ibm.mq.jakarta.jms.MQQueue;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.Queue;
import java.util.Map;
import org.springframework.jms.core.JmsTemplate;

public class JmsLegacyQueueAdapter implements LegacyQueuePort {
  private final JmsTemplate jmsTemplate;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "JmsTemplate is a Spring-managed collaborator.")
  public JmsLegacyQueueAdapter(JmsTemplate jmsTemplate) {
    this.jmsTemplate = jmsTemplate;
    this.jmsTemplate.setDeliveryPersistent(true);
  }

  @Override
  public void put(String queueName, byte[] body, Map<String, String> properties) {
    jmsTemplate.execute(
        (session, producer) -> {
          Queue queue = session.createQueue(queueName);
          ((MQQueue) queue).setTargetClient(WMQConstants.WMQ_TARGET_DEST_MQ);
          BytesMessage message = session.createBytesMessage();
          message.writeBytes(body);
          properties.forEach(
              (key, value) -> {
                if (value != null && !value.isEmpty()) {
                  try {
                    message.setStringProperty(key, value);
                  } catch (JMSException exception) {
                    throw new IllegalStateException(exception);
                  }
                }
              });
          producer.send(queue, message);
          return null;
        });
  }
}
