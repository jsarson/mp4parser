package org.mp4parser.streaming.output.mp4;

public interface CrashlyticsLoggerMP4 {
    void logNonFatal(Exception e);

    void log(String message);
}
