package com.wine.to.up.commonlib.messaging;

import com.wine.to.up.commonlib.annotations.InjectEventLogger;
import com.wine.to.up.commonlib.concurrency.NamedThreadFactory;
import com.wine.to.up.commonlib.logging.CommonNotableEvents;
import com.wine.to.up.commonlib.logging.EventLogger;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * BaseKafkaHandler subscribes consumer to a given topic and then polls the given {@link KafkaConsumer}
 * and delegates message to the underlying  {@link KafkaMessageHandler} for handling.
 * <p>
 * NOTE! As the handling is performed in {@link BaseKafkaHandler} execution thread, handling should not
 * be a very time-consuming operation. Otherwise, it should be performed in separate thread, but it can lead to
 * losing messages. {@see KafkaConsumer} at least once delivery semantics.
 * <p>
 * Usage:
 * You should configure following bean in your application context:
 *
 * <pre>
 *    {@code @}Bean
 *     BaseKafkaHandler<KafkaMessageSentEvent> testTopicMessagesHandler(Properties consumerProperties,
 *                                               DemoServiceApiProperties demoServiceApiProperties,
 *                                               TestTopicKafkaMessageHandler handler) {
 *         // set appropriate deserializer for value
 *         consumerProperties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, EventDeserializer.class.getName());
 *
 *         // bind consumer with topic name and with appropriate handler
 *         return new BaseKafkaHandler<>(demoServiceApiProperties.getMessageSentEventsTopicName(), new KafkaConsumer<>(consumerProperties), handler);
 *     }
 * </pre>
 * <p>
 * The code above:
 * <ul>
 * <li>Creates consumer based on general properties.</li>
 * <li>Uses custom deserializer as the messages within single topic should be the same type. And
 * the messages in different topics can have different types and require different deserializers</li>
 * <li>Binds the consumer of the topic with the object which is responsible for handling messages from
 * this topic</li>
 * <li>From now on all the messages consumed from given topic will be delegate
 * to {@link KafkaMessageHandler#handle(Object)} of the given handler</li>
 * </ul>
 *
 * @param <MessageType> - type of the message
 */
@Slf4j
public class BaseKafkaHandler<MessageType> {

    private static final String THREAD_NAME_PREFIX = "kafka-listener-thread-";

    private final String topicName;
    private final KafkaConsumer<String, MessageType> kafkaConsumer;
    private final KafkaMessageHandler<MessageType> delegateTo;
    private final ExecutorService executor;
    @InjectEventLogger
    @SuppressWarnings("unused")
    private EventLogger eventLogger;

    public BaseKafkaHandler(String topicName,
                            KafkaConsumer<String, MessageType> kafkaConsumer,
                            KafkaMessageHandler<MessageType> delegateTo) {
        this.topicName = topicName;
        this.kafkaConsumer = kafkaConsumer;
        this.delegateTo = delegateTo;
        this.executor = Executors.newSingleThreadExecutor(new NamedThreadFactory(THREAD_NAME_PREFIX + topicName));
    }

    @PostConstruct
    void init() {
        kafkaConsumer.subscribe(Collections.singletonList(topicName));
        executor.submit(this::pollingMessages);
    }

    private void pollingMessages() {
        while (!Thread.interrupted()) {
            try {
                ConsumerRecords<String, MessageType> consumerRecords = kafkaConsumer.poll(Duration.ofSeconds(5)); // todo sukhoa add parameter poll interval?
                consumeMessages(consumerRecords);
            } catch (Exception e) {
                eventLogger.error(CommonNotableEvents.F_KAFKA_CONSUMER_POLL_FAILED, topicName, e);
            }
        }
        eventLogger.warn(CommonNotableEvents.W_KAFKA_LISTENER_INTERRUPTED, topicName);
        kafkaConsumer.close();
    }

    private void consumeMessages(ConsumerRecords<String, MessageType> consumerRecords) {
        for (ConsumerRecord<String, MessageType> consumerRecord : consumerRecords) {
            log.debug("Received message: " + consumerRecord);
            try {
                delegateTo.handle(consumerRecord.value());
            } catch (Exception e) {
                log.warn("Error in kafka handler {}, message lost in topic {}",
                        delegateTo.getClass().getSimpleName(), topicName); // todo sukhoa event logger? or better just lost message counter?
            }
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        executor.shutdownNow();
        eventLogger.warn(CommonNotableEvents.W_EXECUTOR_SHUT_DOWN, THREAD_NAME_PREFIX + topicName);
    }
}
