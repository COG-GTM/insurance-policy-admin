package com.acme.insurance.pas.batch.transfer;

import com.acme.insurance.pas.batch.extract.ExtractOutputs;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/** Final job step: delivers the run's files to one destination and records the receipts. */
public class DeliveryStepTasklet implements Tasklet {

  private final SecureFileTransfer transfer;
  private final String destination;
  private final Function<LocalDate, List<DeliveryFile>> files;

  public DeliveryStepTasklet(
      SecureFileTransfer transfer,
      String destination,
      Function<LocalDate, List<DeliveryFile>> files) {
    this.transfer = transfer;
    this.destination = destination;
    this.files = files;
  }

  @Override
  public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
    LocalDate businessDate = ExtractOutputs.businessDate(chunkContext);
    List<DeliveryReceipt> receipts = transfer.deliver(destination, files.apply(businessDate));
    var context = chunkContext.getStepContext().getStepExecution().getExecutionContext();
    context.putString("delivery.destination", destination);
    context.putInt("delivery.files", receipts.size());
    for (int i = 0; i < receipts.size(); i++) {
      context.putString("delivery." + i + ".remotePath", receipts.get(i).remotePath());
      context.putString("delivery." + i + ".sha256", receipts.get(i).sha256());
    }
    contribution.incrementWriteCount(receipts.size());
    return RepeatStatus.FINISHED;
  }
}
