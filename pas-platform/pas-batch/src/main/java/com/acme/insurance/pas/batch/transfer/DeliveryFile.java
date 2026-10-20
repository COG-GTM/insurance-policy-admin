package com.acme.insurance.pas.batch.transfer;

import java.nio.file.Path;

/** A local file and the name it must have in the destination's remote directory. */
public record DeliveryFile(Path localPath, String remoteName) {}
