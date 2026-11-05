package com.acme.insurance.pas.policyservice.events;

import com.acme.insurance.pas.persistence.EventOutboxJdbcRepository;
import com.acme.insurance.pas.persistence.ParkedEvent;
import com.acme.insurance.pas.policyservice.bridge.DeadLetterService;
import com.acme.insurance.pas.policyservice.bridge.DeadLetterService.DeadLetterPage;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dead-letter handling and replay (AB-528). GETs need {@code pas.ops.read}, POSTs {@code
 * pas.events.replay}. Responses carry ids, topics and errors, never event payloads.
 */
@RestController
@RequestMapping("/api/v1/ops/events")
public class EventOpsController {

  private static final Logger log = LoggerFactory.getLogger(EventOpsController.class);
  private static final Pattern EVENT_ID = Pattern.compile("[0-9a-f-]{36}");
  static final int MAX_REPLAY = 500;

  /** A parked outbox row, without its payload. */
  public record ParkedView(
      String eventId,
      String eventType,
      String policyNumber,
      int attempts,
      String lastError,
      LocalDateTime createdAt,
      LocalDateTime parkedAt,
      int replayCount) {

    static ParkedView from(ParkedEvent p) {
      return new ParkedView(
          p.row().eventId(),
          p.row().eventType(),
          p.row().messageKey(),
          p.row().attempts(),
          p.row().lastError(),
          p.row().createdAt(),
          p.parkedAt(),
          p.replayCount());
    }
  }

  /** One page of parked rows; pass {@code nextAfter} as {@code after} for the next page. */
  public record ParkedPage(List<ParkedView> events, Long nextAfter) {
    public ParkedPage {
      events = List.copyOf(events);
    }
  }

  public record ReplayRequest(List<String> eventIds) {
    public ReplayRequest {
      eventIds = eventIds == null ? null : Collections.unmodifiableList(new ArrayList<>(eventIds));
    }
  }

  public record ReplayResult(List<String> replayed) {
    public ReplayResult {
      replayed = List.copyOf(replayed);
    }
  }

  private final EventOutboxJdbcRepository outbox;
  private final ObjectProvider<DeadLetterService> deadLetters;

  public EventOpsController(
      EventOutboxJdbcRepository outbox, ObjectProvider<DeadLetterService> deadLetters) {
    this.outbox = outbox;
    this.deadLetters = deadLetters;
  }

  @GetMapping("/outbox/parked")
  public ResponseEntity<ParkedPage> parked(
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "" + MAX_REPLAY) int limit) {
    if (after < 0 || limit < 1 || limit > MAX_REPLAY) {
      return ResponseEntity.badRequest().build();
    }
    List<ParkedEvent> rows = outbox.parked(after, limit + 1);
    boolean more = rows.size() > limit;
    List<ParkedEvent> page = more ? rows.subList(0, limit) : rows;
    Long nextAfter = more ? page.get(page.size() - 1).row().outboxSeq() : null;
    return ResponseEntity.ok(
        new ParkedPage(page.stream().map(ParkedView::from).toList(), nextAfter));
  }

  @PostMapping("/outbox/{eventId}/replay")
  public ResponseEntity<ReplayResult> replayOutbox(
      @PathVariable String eventId, Authentication caller) {
    if (!EVENT_ID.matcher(eventId).matches() || !outbox.replay(eventId)) {
      return ResponseEntity.notFound().build();
    }
    log.info("outbox event queued for replay: eventId={} by={}", eventId, caller.getName());
    return ResponseEntity.accepted().body(new ReplayResult(List.of(eventId)));
  }

  @GetMapping("/dead-letters")
  public ResponseEntity<DeadLetterPage> deadLetters(
      @RequestParam(required = false) String after,
      @RequestParam(defaultValue = "" + MAX_REPLAY) int limit) {
    DeadLetterService service = deadLetters.getIfAvailable();
    if (service == null) {
      return ResponseEntity.notFound().build();
    }
    if (limit < 1 || limit > MAX_REPLAY || (after != null && !DeadLetterService.isCursor(after))) {
      return ResponseEntity.badRequest().build();
    }
    return ResponseEntity.ok(service.list(after, limit));
  }

  @PostMapping("/dead-letters/replay")
  public ResponseEntity<ReplayResult> replayDeadLetters(
      @RequestBody ReplayRequest request, Authentication caller) throws Exception {
    DeadLetterService service = deadLetters.getIfAvailable();
    if (service == null) {
      return ResponseEntity.notFound().build();
    }
    List<String> ids = request.eventIds() == null ? List.of() : request.eventIds();
    if (ids.isEmpty()
        || ids.size() > MAX_REPLAY
        || !ids.stream().allMatch(id -> id != null && EVENT_ID.matcher(id).matches())) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }
    List<String> replayed = service.replay(Set.copyOf(ids));
    log.info("dead letters replayed: count={} by={}", replayed.size(), caller.getName());
    return ResponseEntity.accepted().body(new ReplayResult(replayed));
  }
}
