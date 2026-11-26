package pzreversemapper.converter;

import java.io.IOException;

/** Raised when input data is malformed or does not match the requested workflow. */
public final class InvalidDataException extends IOException {
    private static final long serialVersionUID = 1L;

    public InvalidDataException(String message) {
        super(message);
    }
}
