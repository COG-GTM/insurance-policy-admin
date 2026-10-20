package com.acme.insurance.pas.policy.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Ordering: a failed send stops the batch before any later row is sent. */
class OutboxRelayTest {

  @Test
  @SuppressWarnings("unchecked")
  void failedSendIsNeverOvertakenByALaterRow() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
    when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    when(jdbc.queryForObject(
            "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, OutboxRelay.LOCK_KEY))
        .thenReturn(true);
    ResultSet rs = mock(ResultSet.class);
    when(rs.getLong(1)).thenReturn(10L, 11L);
    when(rs.getString(2)).thenReturn("e10", "e11");
    when(rs.getString(3)).thenReturn("pas.policy.events.v1");
    when(rs.getString(4)).thenReturn("POL001000000");
    when(rs.getString(5)).thenReturn("PolicyCreated", "UnderwritingDecided");
    when(rs.getString(6)).thenReturn("{}");
    when(jdbc.query(anyString(), any(RowMapper.class), anyInt()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              List<Object> rows = new ArrayList<>();
              rows.add(mapper.mapRow(rs, 0));
              rows.add(mapper.mapRow(rs, 1));
              return rows;
            });
    when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(
            CompletableFuture.<SendResult<String, String>>failedFuture(
                new IllegalStateException("broker down")),
            CompletableFuture.completedFuture(null));

    OutboxRelay relay =
        new OutboxRelay(jdbc, tm, kafka, new OutboxRelayProperties(true, null, 0, null));
    assertThat(relay.relayOnce()).isZero();

    ArgumentCaptor<ProducerRecord<String, String>> sent =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafka, times(1)).send(sent.capture());
    assertThat(new String(sent.getValue().headers().lastHeader("eventId").value()))
        .isEqualTo("e10");
    verify(jdbc)
        .update(
            eq("UPDATE pas.outbox SET attempts = attempts + 1, last_error = ? WHERE outbox_id = ?"),
            anyString(),
            eq(10L));
  }
}
