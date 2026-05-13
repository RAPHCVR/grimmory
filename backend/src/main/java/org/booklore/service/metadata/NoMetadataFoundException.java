package org.booklore.service.metadata;

public class NoMetadataFoundException extends RuntimeException {

    public NoMetadataFoundException(String message) {
        super(message);
    }
}
