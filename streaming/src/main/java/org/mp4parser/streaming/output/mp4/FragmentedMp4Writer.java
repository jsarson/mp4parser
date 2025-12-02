package org.mp4parser.streaming.output.mp4;

import static java.lang.Math.min;

import org.mp4parser.Box;
import org.mp4parser.IsoFile;
import org.mp4parser.boxes.iso14496.part12.MediaHeaderBox;
import org.mp4parser.boxes.iso14496.part12.MovieBox;
import org.mp4parser.boxes.iso14496.part12.MovieExtendsBox;
import org.mp4parser.boxes.iso14496.part12.MovieExtendsHeaderBox;
import org.mp4parser.boxes.iso14496.part12.MovieFragmentBox;
import org.mp4parser.boxes.iso14496.part12.MovieFragmentHeaderBox;
import org.mp4parser.boxes.iso14496.part12.MovieHeaderBox;
import org.mp4parser.boxes.iso14496.part12.SampleFlags;
import org.mp4parser.boxes.iso14496.part12.TrackExtendsBox;
import org.mp4parser.boxes.iso14496.part12.TrackFragmentBaseMediaDecodeTimeBox;
import org.mp4parser.boxes.iso14496.part12.TrackFragmentBox;
import org.mp4parser.boxes.iso14496.part12.TrackFragmentHeaderBox;
import org.mp4parser.boxes.iso14496.part12.TrackRunBox;
import org.mp4parser.boxes.samplegrouping.SampleGroupDescriptionBox;
import org.mp4parser.boxes.samplegrouping.SampleToGroupBox;
import org.mp4parser.streaming.StreamingSample;
import org.mp4parser.streaming.StreamingTrack;
import org.mp4parser.streaming.extensions.DefaultSampleFlagsTrackExtension;
import org.mp4parser.streaming.extensions.SampleFlagsSampleExtension;
import org.mp4parser.streaming.extensions.TrackIdTrackExtension;
import org.mp4parser.streaming.output.SampleSink;
import org.mp4parser.tools.IsoTypeWriter;
import org.mp4parser.tools.Mp4Arrays;
import org.mp4parser.tools.Mp4Math;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fragmented MP4 writer.
 * Logic: Wait until 2 keyframes in video buffer, then cut everything up to (but excluding) the 2nd keyframe.
 * Audio is cut to match the same duration.
 *
 * Extended to mimic iOS approach: includes sgpd/sbgp boxes for RAP (video) and roll (audio).
 * Fixed drift: use integer LCM-tick alignment with error feedback (no ms rounding).
 */
public class FragmentedMp4Writer extends DefaultBoxes implements SampleSink {
    private static final Logger LOG = LoggerFactory.getLogger(FragmentedMp4Writer.class.getName());

    protected final WritableByteChannel sink;
    protected final List<StreamingTrack> source;
    protected final Date creationTime;

    protected volatile boolean headerWritten = false;
    protected long bytesWritten = 0;
    protected long sequenceNumber = 1;

    // Buffers & timing
    protected final Map<StreamingTrack, List<StreamingSample>> sampleBuffers = new HashMap<>();
    protected final Map<StreamingTrack, Long> nextFragmentStartTs = new ConcurrentHashMap<>();
    protected final Map<StreamingTrack, Long> nextSampleStartTs = new ConcurrentHashMap<>();

    private WriterOutputCallback outputCallback;

    // LCM-based alignment accumulator to eliminate long-term drift (in LCM ticks)
    private long lcmTimescale = 0;
    private long audioSyncErrLcm = 0; // carried between fragments to decide 93/94 AAC frames, etc.

    /**
     * Error compensation for segment duration reporting.
     * Ensures that the sum of reported durations matches the true total duration in ticks.
     * This prevents rounding errors from accumulating over time.
     *
     * Why is this needed?
     * When converting segment durations from timescale ticks to milliseconds, rounding errors occur.
     * For example, with timescale=90000 and segment duration=3003 ticks:
     *   (3003 * 1000) / 90000 = 33.366... ms → rounded to 33 ms
     * Max error per segment: ~0.366 ms
     * After 10,000 segments: 0.366 ms * 10,000 = 3,660 ms = 3.66 s (as s theoretical maximum, it reality will be much lower)
     * Without compensation, the backend would see a drift of a small seconds per day.
     * This mechanism ensures the total reported duration always matches the true duration.
     */
    private long totalVideoTicksReported = 0;
    private long totalVideoMsReported = 0;

    public FragmentedMp4Writer(List<StreamingTrack> source, WritableByteChannel sink) throws IOException {
        this.source = new LinkedList<>(source);
        this.sink = sink;
        this.creationTime = new Date();

        HashSet<Long> ids = new HashSet<>();
        for (StreamingTrack t : source) {
            t.setSampleSink(this);
            sampleBuffers.put(t, new ArrayList<>());
            nextFragmentStartTs.put(t, 0L);
            nextSampleStartTs.put(t, 0L);
            TrackIdTrackExtension tie = t.getTrackExtension(TrackIdTrackExtension.class);
            if (tie != null) {
                if (ids.contains(tie.getTrackId())) throw new IOException("Duplicate trackID in one file");
                ids.add(tie.getTrackId());
            }
        }
        for (StreamingTrack t : source) {
            if (t.getTrackExtension(TrackIdTrackExtension.class) == null) {
                long max = ids.stream().max(Long::compare).orElse(0L);
                TrackIdTrackExtension tiExt = new TrackIdTrackExtension(max + 1);
                t.addTrackExtension(tiExt);
                ids.add(tiExt.getTrackId());
            }
        }
    }

    public void setOutputCallback(WriterOutputCallback cb) {
        this.outputCallback = cb;
    }

    @Override
    public synchronized void acceptSample(StreamingSample sample, StreamingTrack track) throws IOException {
        writeHeaderIfReady(track);
        sampleBuffers.get(track).add(sample);
        nextSampleStartTs.put(track, nextSampleStartTs.get(track) + sample.getDuration());
        maybeFlushFragment(false);
    }

    @Override
    public synchronized void close() throws IOException {
        maybeFlushFragment(true);
        writeFooter(createFooter());
        if (outputCallback != null) outputCallback.onSegmentReady(null, 0, false, true);
    }

    protected void writeHeader(Box... boxes) throws IOException {
        write(sink, boxes);
    }

    protected void writeFragment(Box... boxes) throws IOException {
        write(sink, boxes);
    }

    protected void writeFooter(Box... boxes) throws IOException {
        write(sink, boxes);
    }

    private void write(WritableByteChannel out, Box... boxes) throws IOException {
        for (Box b : boxes) {
            b.getBox(out);
            bytesWritten += b.getSize();
        }
    }

    private synchronized void writeHeaderIfReady(StreamingTrack triggering) throws IOException {
        if (headerWritten) return;
        for (StreamingTrack t : source) {
            List<StreamingSample> buf = sampleBuffers.get(t);
            if (buf == null || buf.isEmpty()) return;
        }
        writeHeader(createHeader());
        headerWritten = true;
        if (outputCallback != null) outputCallback.onSegmentReady(null, 0, true, false);
    }

    protected Box[] createHeader() {
        return new Box[]{createFtyp(), createMoov()};
    }
    protected Box createMoov() {
        MovieBox moov = new MovieBox();
        moov.addBox(createMvhd());
        for (StreamingTrack t : source) moov.addBox(createTrak(t));
        moov.addBox(createMvex());
        return moov;
    }
    protected Box createMvex() {
        MovieExtendsBox mvex = new MovieExtendsBox();
        MovieExtendsHeaderBox mved = new MovieExtendsHeaderBox();
        mved.setVersion(1);
        mved.setFragmentDuration(0);
        mvex.addBox(mved);
        for (StreamingTrack t : source) mvex.addBox(createTrex(t));
        return mvex;
    }
    protected Box createTrex(StreamingTrack t) {
        TrackExtendsBox trex = new TrackExtendsBox();
        trex.setTrackId(t.getTrackExtension(TrackIdTrackExtension.class).getTrackId());
        trex.setDefaultSampleDescriptionIndex(1);
        trex.setDefaultSampleDuration(0);
        trex.setDefaultSampleSize(0);
        trex.setDefaultSampleFlags(new SampleFlags());
        return trex;
    }
    protected Box createMvhd() {
        MovieHeaderBox mvhd = new MovieHeaderBox();
        mvhd.setVersion(1);
        mvhd.setCreationTime(creationTime);
        mvhd.setModificationTime(creationTime);
        mvhd.setDuration(0);
        long[] timescales = new long[0];
        long maxTrackId = 0;
        for (StreamingTrack t : source) {
            timescales = Mp4Arrays.copyOfAndAppend(timescales, t.getTimescale());
            maxTrackId = Math.max(maxTrackId, t.getTrackExtension(TrackIdTrackExtension.class).getTrackId());
        }
        mvhd.setTimescale(Mp4Math.lcm(timescales));
        mvhd.setNextTrackId(maxTrackId + 1);
        return mvhd;
    }
    protected Box createMdhd(StreamingTrack t) {
        MediaHeaderBox mdhd = new MediaHeaderBox();
        mdhd.setCreationTime(creationTime);
        mdhd.setModificationTime(creationTime);
        mdhd.setDuration(0);
        mdhd.setTimescale(t.getTimescale());
        mdhd.setLanguage(t.getLanguage());
        return mdhd;
    }

    private List<StreamingSample> getVideoBuffer() {
        return sampleBuffers.get(findVideoTrack());
    }

    private List<StreamingSample> getAudioBuffer() {
        return sampleBuffers.get(findAudioTrack());
    }

    int abnormalNumberOfKeyFramesCounter = 0;

    private synchronized void maybeFlushFragment(boolean force) throws IOException {
        StreamingTrack v = findVideoTrack();
        StreamingTrack a = findAudioTrack();
        if (v == null || a == null) return;

        List<StreamingSample> vBuf = sampleBuffers.get(v);
        List<StreamingSample> aBuf = sampleBuffers.get(a);
        if (vBuf.isEmpty() || aBuf.isEmpty()) return;

        // compute/update LCM timescale for stable integer math
        long lcm = Mp4Math.lcm(new long[]{v.getTimescale(), a.getTimescale()});
        if (lcmTimescale == 0) lcmTimescale = lcm;

        // find first two keyframes in vBuf
        int firstKey = -1, secondKey = -1;
        for (int i = 0; i < vBuf.size(); i++) {
            if (isKeyframeSample(vBuf.get(i))) {
                if (firstKey == -1) firstKey = i;
                else {
                    secondKey = i;
                    break;
                }
            }
        }
        if (firstKey != 0) throw new IllegalStateException("this can't happen");
        if (firstKey == -1 || secondKey == -1) {
            if (!force) return;
            secondKey = vBuf.size();
        }

        if (countKeyFrames() > 2) {
            abnormalNumberOfKeyFramesCounter += 1;
        }

        // select video samples up to (but not including) second keyframe
        List<StreamingSample> vSel = new ArrayList<>(vBuf.subList(firstKey, secondKey));
        if (vSel.isEmpty()) return;

        // exact video duration in ticks and in LCM ticks
        long vDur = sumDur(vSel);
        long vDurLcm = scaleTo(vDur, v.getTimescale(), lcm);

        // === Select AUDIO to match VIDEO in LCM ticks using error feedback (Bresenham-like) ===
        long desiredAudioTotalLcm = vDurLcm + audioSyncErrLcm;
        List<StreamingSample> aSel = new ArrayList<>();
        long aAccLcm = 0;
        int aIdx = 0;
        while (aIdx < aBuf.size()) {
            StreamingSample s = aBuf.get(aIdx);
            long sLcm = scaleTo(s.getDuration(), a.getTimescale(), lcm);
            if (aAccLcm + sLcm <= desiredAudioTotalLcm) {
                aSel.add(s);
                aAccLcm += sLcm;
                aIdx++;
            } else {
                break; // adding next sample would overshoot desired total
            }
        }
        if (aSel.isEmpty()) return;

        // update error accumulator so cumulative audio == cumulative video over time
        audioSyncErrLcm = desiredAudioTotalLcm - aAccLcm; // bounded by < one audio sample in LCM ticks

        long vBase = nextFragmentStartTs.get(v);
        long aBase = nextFragmentStartTs.get(a);

        MovieFragmentBox moof = new MovieFragmentBox();
        MovieFragmentHeaderBox mfhd = new MovieFragmentHeaderBox();
        mfhd.setSequenceNumber(sequenceNumber);
        moof.addBox(mfhd);

        createTrafWithGroups(v, moof, vSel, vBase, true);
        createTrafWithGroups(a, moof, aSel, aBase, false);

        List<TrackRunBox> truns = moof.getTrackRunBoxes();
        for (TrackRunBox tr : truns) tr.setDataOffset(1);

        long moofSize = moof.getSize();
        int firstPayloadOffset = (int) (8 + moofSize);
        long vBytes = sumBytes(vSel);

        if (!truns.isEmpty()) truns.get(0).setDataOffset(firstPayloadOffset);
        if (truns.size() > 1) truns.get(1).setDataOffset((int) (firstPayloadOffset + vBytes));

        Box mdat = createMdat(vSel, aSel);
        writeFragment(moof, mdat);
        sequenceNumber++;

        long vUsed = sumDur(vSel);
        long aUsed = sumDur(aSel);
        nextFragmentStartTs.put(v, vBase + vUsed);
        nextFragmentStartTs.put(a, aBase + aUsed);

        vBuf.subList(0, secondKey).clear();
        aBuf.subList(0, aSel.size()).clear();

        // === Accurate segment duration reporting with error compensation ===
        if (outputCallback != null) {
            // Accumulate total ticks
            totalVideoTicksReported += vUsed;
            // Calculate ideal total ms
            long idealTotalMs = (totalVideoTicksReported * 1000L) / v.getTimescale();
            // Calculate segment ms (rounded)
            long segmentMs = (vUsed * 1000L) / v.getTimescale();
            // Calculate compensated segment ms so that total always matches ideal
            long compensatedSegmentMs = idealTotalMs - totalVideoMsReported;
            // Update reported total
            totalVideoMsReported = idealTotalMs;
            // Report compensated segment duration
            outputCallback.onSegmentReady(v, compensatedSegmentMs, false, false);
        }
    }

    private void createTrafWithGroups(StreamingTrack track, MovieFragmentBox moof, List<StreamingSample> samples, long baseTs, boolean isVideo) {
        TrackFragmentBox traf = new TrackFragmentBox();
        moof.addBox(traf);
        createTfhd(track, traf);
        createTfdt(track, traf, baseTs);

        // add RAP sample group for video or ROLL for audio
        if (isVideo) {
            SampleGroupDescriptionBox sgpd = new SampleGroupDescriptionBox();
            sgpd.setGroupingType("rap ");
            SampleToGroupBox sbgp = new SampleToGroupBox();
            sbgp.setGroupingType("rap ");
            sbgp.setEntries(Collections.singletonList(new SampleToGroupBox.Entry(samples.size(), 1)));
            traf.addBox(sgpd);
            traf.addBox(sbgp);
        } else {
            SampleGroupDescriptionBox sgpd = new SampleGroupDescriptionBox();
            sgpd.setGroupingType("roll");
            SampleToGroupBox sbgp = new SampleToGroupBox();
            sbgp.setGroupingType("roll");
            sbgp.setEntries(Collections.singletonList(new SampleToGroupBox.Entry(samples.size(), 1)));
            traf.addBox(sgpd);
            traf.addBox(sbgp);
        }

        createTrun(track, traf, samples);
    }

    protected void createTfhd(StreamingTrack track, TrackFragmentBox parent) {
        TrackFragmentHeaderBox tfhd = new TrackFragmentHeaderBox();
        tfhd.setTrackId(track.getTrackExtension(TrackIdTrackExtension.class).getTrackId());
        tfhd.setDefaultBaseIsMoof(true);
        DefaultSampleFlagsTrackExtension def = track.getTrackExtension(DefaultSampleFlagsTrackExtension.class);
        SampleFlags sf = new SampleFlags();
        if (def != null) {
            sf.setIsLeading(def.getIsLeading());
            sf.setSampleIsDependedOn(def.getSampleIsDependedOn());
            sf.setSampleDependsOn(def.getSampleDependsOn());
            sf.setSampleHasRedundancy(def.getSampleHasRedundancy());
            sf.setSampleIsDifferenceSample(def.isSampleIsNonSyncSample());
            sf.setSamplePaddingValue(def.getSamplePaddingValue());
            sf.setSampleDegradationPriority(def.getSampleDegradationPriority());
        }
        tfhd.setDefaultSampleFlags(sf);
        parent.addBox(tfhd);
    }

    protected void createTfdt(StreamingTrack track, TrackFragmentBox parent, long baseTs) {
        TrackFragmentBaseMediaDecodeTimeBox tfdt = new TrackFragmentBaseMediaDecodeTimeBox();
        tfdt.setVersion(1);
        tfdt.setBaseMediaDecodeTime(baseTs);
        parent.addBox(tfdt);
    }

    protected void createTrun(StreamingTrack track, TrackFragmentBox parent, List<StreamingSample> samples) {
        TrackRunBox trun = new TrackRunBox();
        trun.setVersion(1);
        trun.setSampleDurationPresent(true);
        trun.setSampleSizePresent(true);
        trun.setSampleCompositionTimeOffsetPresent(false);
        trun.setSampleFlagsPresent(true);
        trun.setDataOffsetPresent(true);

        List<TrackRunBox.Entry> entries = new ArrayList<>(samples.size());
        for (StreamingSample s : samples) {
            TrackRunBox.Entry e = new TrackRunBox.Entry();
            e.setSampleSize(s.getContent().limit());
            e.setSampleDuration(s.getDuration());
            e.setSampleFlags(buildFlags(isKeyframeSample(s)));
            entries.add(e);
        }
        trun.setEntries(entries);
        parent.addBox(trun);
    }

    private SampleFlags buildFlags(boolean key) {
        SampleFlags f = new SampleFlags();
        f.setSampleDependsOn(key ? 2 : 1);
        f.setSampleIsDifferenceSample(!key);
        return f;
    }

    private Box createMdat(final List<StreamingSample> v, final List<StreamingSample> a) {
        return new Box() {
            public String getType() { return "mdat"; }
            public long getSize() { return 8 + sumBytes(v) + sumBytes(a); }
            public void getBox(WritableByteChannel ch) throws IOException {
                long sz = getSize();
                ByteBuffer bb = ByteBuffer.allocate(8);
                IsoTypeWriter.writeUInt32(bb, sz);
                bb.put(IsoFile.fourCCtoBytes(getType()));
                ch.write((ByteBuffer) ((Buffer) bb).rewind());
                for (StreamingSample s : v) ch.write((ByteBuffer) ((Buffer) s.getContent()).rewind());
                for (StreamingSample s : a) ch.write((ByteBuffer) ((Buffer) s.getContent()).rewind());
            }
        }; }

    protected Box[] createFooter() { return new Box[0]; }

    private StreamingTrack findVideoTrack() {
        for (StreamingTrack t : source) {
            if (t instanceof CameritoStream s && s.getType() == CameritoStream.Type.VIDEO) return t;
        }
        return null;
    }
    private StreamingTrack findAudioTrack() {
        for (StreamingTrack t : source) {
            if (t instanceof CameritoStream s && s.getType() == CameritoStream.Type.AUDIO) return t;
        }
        return null;
    }

    private long sumDur(List<StreamingSample> list) {
        long d = 0;
        for (StreamingSample s : list) d += s.getDuration();
        return d;
    }

    private long sumBytes(List<StreamingSample> list) {
        long s = 0;
        for (StreamingSample smp : list) s += smp.getContent().limit();
        return s;
    }

    // Integer scaling helper: convert timestamp from fromScale to toScale using floor (no rounding up)
    private long scaleTo(long ts, long fromScale, long toScale) {
        if (fromScale == toScale) return ts;
        return (ts * toScale) / fromScale; // floor to ensure audio never runs ahead of video
    }

    private boolean isKeyframeSample(StreamingSample s) {
        SampleFlagsSampleExtension f = s.getSampleExtension(SampleFlagsSampleExtension.class);
        return f == null || f.isSyncSample();
    }

    public long countKeyFrames() {
        var video = getVideoBuffer();
        return video.stream().filter(this::isKeyframeSample).count();
    }

    public String stats() {
        var audio = getAudioBuffer();
        var video = getVideoBuffer();
        var keyCount = countKeyFrames();

        StringBuilder b = new StringBuilder();
        for (int i = 0; i < min(60, video.size()); i++) {
            b.append(isKeyframeSample(video.get(i)) ? '1' : '0');
        }
        if (video.size() > 60) b.append("...");

        return String.format("video=%s, audio=%s, video-key=%s, abnormal-key-frames=%s, video-buf=%s", video.size(), audio.size(), keyCount, abnormalNumberOfKeyFramesCounter, b);
    }
}
