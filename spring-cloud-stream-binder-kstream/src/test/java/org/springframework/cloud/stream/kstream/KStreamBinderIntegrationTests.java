package org.springframework.cloud.stream.kstream;

import java.util.Arrays;
import java.util.Date;
import java.util.Map;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.cloud.stream.kstream.annotations.KStreamProcessor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.rule.KafkaEmbedded;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.messaging.handler.annotation.SendTo;

/**
 * @author Soby Chacko
 */
public class KStreamBinderIntegrationTests {

	@ClassRule
	public static KafkaEmbedded embeddedKafka = new KafkaEmbedded(1, true, "counts");

	private static Consumer<String, String> consumer;

	@BeforeClass
	public static void setUp() throws Exception {
		Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("group", "false", embeddedKafka);
		consumerProps.put("key.deserializer", StringDeserializer.class);
		consumerProps.put("value.deserializer", StringDeserializer.class);
		consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		DefaultKafkaConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(consumerProps);
		consumer = cf.createConsumer();
		embeddedKafka.consumeFromAnEmbeddedTopic(consumer, "counts");
	}

	@AfterClass
	public static void tearDown() {
		consumer.close();
	}

	@Test
	public void testFoo() throws Exception {
		ConfigurableApplicationContext context = SpringApplication.run(WordCountProcessorApplication.class, "--server.port=0",
				"--spring.cloud.stream.bindings.input.destination=words", "--spring.cloud.stream.bindings.output.destination=counts",
				"--spring.cloud.stream.bindings.output.contentType=application/json", "--spring.cloud.stream.kstream.binder.streamConfiguration.commit.interval.ms=1000",
				"--spring.cloud.stream.kstream.binder.streamConfiguration.key.serde=org.apache.kafka.common.serialization.Serdes$StringSerde",
				"--spring.cloud.stream.kstream.binder.streamConfiguration.value.serde=org.apache.kafka.common.serialization.Serdes$StringSerde",
				"--spring.cloud.stream.bindings.output.producer.headerMode=raw",
				"--spring.cloud.stream.bindings.output.producer.useNativeEncoding=true",
				"--spring.cloud.stream.bindings.input.consumer.headerMode=raw",
				"--spring.profiles.active=standalone",
				"--spring.cloud.stream.kstream.binder.brokers=" + embeddedKafka.getBrokersAsString(),
				"--spring.cloud.stream.kstream.binder.zkNodes=" + embeddedKafka.getZookeeperConnectionString());
		receiveAndValidate(context);
		context.close();
	}

	private void receiveAndValidate(ConfigurableApplicationContext context) throws Exception{
		Map<String, Object> senderProps = KafkaTestUtils.producerProps(embeddedKafka);
		DefaultKafkaProducerFactory<Integer, String> pf = new DefaultKafkaProducerFactory<>(senderProps);
		KafkaTemplate<Integer, String> template = new KafkaTemplate<>(pf, true);

		template.setDefaultTopic("words");

		template.sendDefault("foobar");

		ConsumerRecord<String, String> cr = KafkaTestUtils.getSingleRecord(consumer, "counts");
	}

	@EnableBinding(KStreamProcessor.class)
	@EnableConfigurationProperties(WordCountProcessorProperties.class)
	@EnableAutoConfiguration
	public static class WordCountProcessorApplication {

		@Autowired
		private WordCountProcessorProperties processorProperties;

		@StreamListener("input")
		@SendTo("output")
		public KStream<?, WordCount> process(KStream<?, String> input) {
			System.out.println("Hi there:" + input);

			return input
					.flatMapValues(value -> Arrays.asList(value.toLowerCase().split("\\W+")))
					.map((key, word) -> new KeyValue<>(word, word))
					.groupByKey(Serdes.String(), Serdes.String())
					.count(configuredTimeWindow(), processorProperties.getStoreName())
					.toStream()
					.map((w, c) -> new KeyValue<>(null, new WordCount(w.key(), c, new Date(w.window().start()), new Date(w.window().end()))));
		}

		/**
		 * Constructs a {@link TimeWindows} property.
		 *
		 * @return
		 */
		private TimeWindows configuredTimeWindow() {
			return processorProperties.getAdvanceBy() > 0
					? TimeWindows.of(processorProperties.getWindowLength()).advanceBy(processorProperties.getAdvanceBy())
					: TimeWindows.of(processorProperties.getWindowLength());
		}
	}

	@ConfigurationProperties(prefix = "kstream.word.count")
	static class WordCountProcessorProperties {

		private int windowLength = 5000;

		private int advanceBy = 0;

		private String storeName = "WordCounts";

		public int getWindowLength() {
			return windowLength;
		}

		public void setWindowLength(int windowLength) {
			this.windowLength = windowLength;
		}

		public int getAdvanceBy() {
			return advanceBy;
		}

		public void setAdvanceBy(int advanceBy) {
			this.advanceBy = advanceBy;
		}

		public String getStoreName() {
			return storeName;
		}

		public void setStoreName(String storeName) {
			this.storeName = storeName;
		}
	}

	static class WordCount {

		private String word;

		private long count;

		private Date start;

		private Date end;

		public WordCount(String word, long count, Date start, Date end) {
			this.word = word;
			this.count = count;
			this.start = start;
			this.end = end;
		}

		public String getWord() {
			return word;
		}

		public void setWord(String word) {
			this.word = word;
		}

		public long getCount() {
			return count;
		}

		public void setCount(long count) {
			this.count = count;
		}

		public Date getStart() {
			return start;
		}

		public void setStart(Date start) {
			this.start = start;
		}

		public Date getEnd() {
			return end;
		}

		public void setEnd(Date end) {
			this.end = end;
		}
	}

}
