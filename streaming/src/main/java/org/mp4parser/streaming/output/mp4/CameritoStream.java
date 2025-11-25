package org.mp4parser.streaming.output.mp4;

public interface CameritoStream {
    enum Type {
        VIDEO,
        AUDIO,
    }

    Type getType();
}
