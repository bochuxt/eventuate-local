package io.eventuate.local.mysql.binlog;

import io.eventuate.local.common.BinLogEvent;
import io.eventuate.local.common.CdcKafkaPublisher;
import io.eventuate.local.common.PublishingStrategy;
import io.eventuate.local.common.exception.EventuateLocalPublishingException;
import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class MySQLCdcKafkaPublisher<EVENT extends BinLogEvent> extends CdcKafkaPublisher<EVENT> {

  private DatabaseBinlogOffsetKafkaStore binlogOffsetKafkaStore;
  private Logger logger = LoggerFactory.getLogger(this.getClass());
  private DuplicatePublishingDetector duplicatePublishingDetector;

  public MySQLCdcKafkaPublisher(DatabaseBinlogOffsetKafkaStore binlogOffsetKafkaStore, String kafkaBootstrapServers, PublishingStrategy<EVENT> publishingStrategy) {
    super(kafkaBootstrapServers, publishingStrategy);

    this.binlogOffsetKafkaStore = binlogOffsetKafkaStore;
    this.duplicatePublishingDetector = new DuplicatePublishingDetector(kafkaBootstrapServers);
  }

  @Override
  public void handleEvent(EVENT publishedEvent) throws EventuateLocalPublishingException {
    Objects.requireNonNull(publishedEvent);

    logger.trace("Got record " + publishedEvent.toString());

    String aggregateTopic = publishingStrategy.topicFor(publishedEvent);
    String json = publishingStrategy.toJson(publishedEvent);

    Exception lastException = null;

    for (int i = 0; i < 5; i++) {
      try {
        if (duplicatePublishingDetector.shouldBePublished(publishedEvent.getBinlogFileOffset(), aggregateTopic)) {
          producer.send(
                  aggregateTopic,
                  publishingStrategy.partitionKeyFor(publishedEvent),
                  json
          ).get(10, TimeUnit.SECONDS);

          publishingStrategy.getCreateTime(publishedEvent).ifPresent(time -> histogramEventAge.ifPresent(x -> x.set(System.currentTimeMillis() - time)));
          meterEventsPublished.ifPresent(Counter::increment);

          binlogOffsetKafkaStore.save(publishedEvent.getBinlogFileOffset());
        } else {
          logger.debug("Duplicate event {}", publishedEvent);
          meterEventsDuplicates.ifPresent(Counter::increment);
        }
        return;
      } catch (Exception e) {
        logger.warn("error publishing to " + aggregateTopic, e);
        meterEventsRetries.ifPresent(Counter::increment);
        lastException = e;

        try {
          Thread.sleep((int) Math.pow(2, i) * 1000);
        } catch (InterruptedException ie) {
          throw new RuntimeException(ie);
        }
      }
    }
    throw new EventuateLocalPublishingException("error publishing to " + aggregateTopic, lastException);
  }

}
