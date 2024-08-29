package org.mp4parser.streaming.output.mp4;

import org.mp4parser.streaming.StreamingTrack;

public interface WriterOutputCallback {
    void onSegmentReady(StreamingTrack track, long duration, boolean isInit, boolean isFooter);
}
