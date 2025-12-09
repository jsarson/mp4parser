package org.mp4parser.streaming.output.mp4;

import org.mp4parser.streaming.StreamingTrack;

public interface WriterOutputCallback {
    void onSegmentReady(StreamingTrack track, double duration, boolean isInit, boolean isFooter, boolean isForce);
}
