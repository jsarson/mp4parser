package org.mp4parser.streaming.output.mp4;

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
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fragmented MP4 writer (seek-safe for iOS, close to original style).
 * Diagnostic version: **NO DROPPING** of pre-keyframe samples.
 *
 * \- One fragment = one moof (traf video + traf audio) + one mdat.
 * \- Each traf has tfdt (baseMediaDecodeTime) = DTS of the first sample included in that fragment.
 * \- Each trun has correct data_offset (computed after moof size).
 * \- No B-frames (DTS == PTS) => no composition time offsets.
 * \- This build DOES NOT trim to the last IDR. We flush from buffer start.
 */
public class FragmentedMp4Writer extends DefaultBoxes implements SampleSink {
    private static final Logger LOG = LoggerFactory.getLogger(FragmentedMp4Writer.class.getName());

    protected final WritableByteChannel sink;
    protected final List<StreamingTrack> source;
    protected final Date creationTime;

    protected volatile boolean headerWritten = false;
    protected long bytesWritten = 0;
    protected long sequenceNumber = 1;

    private long targetDurationMs = 2000; // target fragment duration in ms

    // Buffers & timing
    protected final Map<StreamingTrack, List<StreamingSample>> sampleBuffers = new HashMap<StreamingTrack, List<StreamingSample>>();
    protected final Map<StreamingTrack, Long> nextFragmentStartTs = new ConcurrentHashMap<StreamingTrack, Long>(); // per-track timescale
    protected final Map<StreamingTrack, Long> nextSampleStartTs = new ConcurrentHashMap<StreamingTrack, Long>(); // per-track timescale

    // optional callback – if present in your codebase
    private WriterOutputCallback outputCallback;

    public void setOutputCallback(WriterOutputCallback cb) {
        this.outputCallback = cb;
    }

    public void setTargetDuration(long ms) {
        if (ms <= 0) throw new IllegalStateException("target duration must be positive");
        this.targetDurationMs = ms;
    }

    public FragmentedMp4Writer(List<StreamingTrack> source, WritableByteChannel sink) throws IOException {
        this.source = new LinkedList<StreamingTrack>(source);
        this.sink = sink;
        this.creationTime = new Date();

        // Init trackId and basic structures
        HashSet<Long> ids = new HashSet<Long>();
        for (StreamingTrack t : source) {
            t.setSampleSink(this);
            sampleBuffers.put(t, new ArrayList<StreamingSample>());
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
                long max = 0;
                for (Long id : ids) max = Math.max(max, id);
                TrackIdTrackExtension tiExt = new TrackIdTrackExtension(max + 1);
                t.addTrackExtension(tiExt);
                ids.add(tiExt.getTrackId());
            }
        }
    }

    // ===== SampleSink =====

    @Override
    public synchronized void acceptSample(StreamingSample sample, StreamingTrack track) throws IOException {
        writeHeaderIfReady(track);
        sampleBuffers.get(track).add(sample);
        nextSampleStartTs.put(track, nextSampleStartTs.get(track) + sample.getDuration());
        maybeFlushFragment(false);
    }

    @Override
    public synchronized void close() throws IOException {
        maybeFlushFragment(true); // force flush last fragment
        writeFooter(createFooter());
        if (outputCallback != null) outputCallback.onSegmentReady(null, 0, false, true);
    }

    // ===== Header / Movie boxes =====

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

        // condition: from EVERY track we have at least ONE sample in buffer
        boolean allHaveAtLeastOne = true;
        for (StreamingTrack t : source) {
            List<StreamingSample> buf = sampleBuffers.get(t);
            if (buf == null || buf.isEmpty()) {
                allHaveAtLeastOne = false;
                break;
            }
        }
        if (!allHaveAtLeastOne) return;

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

    // DefaultBoxes requires this override
    protected Box createMdhd(StreamingTrack t) {
        MediaHeaderBox mdhd = new MediaHeaderBox();
        mdhd.setCreationTime(creationTime);
        mdhd.setModificationTime(creationTime);
        mdhd.setDuration(0);
        mdhd.setTimescale(t.getTimescale());
        mdhd.setLanguage(t.getLanguage());
        return mdhd;
    }

    // ===== Fragmenting (single fragment for video+audio); NO DROPPING =====

    private synchronized void maybeFlushFragment(boolean force) throws IOException {
        StreamingTrack v = findVideoTrack();
        StreamingTrack a = findAudioTrack();
        if (v == null || a == null) return;

        List<StreamingSample> vBuf = sampleBuffers.get(v);
        List<StreamingSample> aBuf = sampleBuffers.get(a);
        if ((vBuf == null || vBuf.isEmpty()) || (aBuf == null || aBuf.isEmpty())) {
            if (!force) return;
        }

        long vStart = nextFragmentStartTs.get(v);
        long aStart = nextFragmentStartTs.get(a);
        long vNow = nextSampleStartTs.get(v);
        long aNow = nextSampleStartTs.get(a);

        long vBufferedMs = toMs(Math.max(0, vNow - vStart), v.getTimescale());
        long aBufferedMs = toMs(Math.max(0, aNow - aStart), a.getTimescale());

        boolean timeEnough = Math.min(vBufferedMs, aBufferedMs) >= targetDurationMs;
        if (!(force || timeEnough)) return;

        long cutMs = force ? Math.min(vBufferedMs, aBufferedMs) : targetDurationMs;

        // Select VIDEO from the beginning of the buffer
        List<StreamingSample> vSel = new ArrayList<>();
        long vAcc = 0;
        for (int i = 0; i < vBuf.size(); i++) {
            StreamingSample s = vBuf.get(i);
            long after = vAcc + s.getDuration();
            if (!force && toMs(after, v.getTimescale()) > cutMs) break;
            vSel.add(s);
            vAcc = after;
        }
        if (vSel.isEmpty()) return;

        // Select AUDIO from the beginning of the buffer
        List<StreamingSample> aSel = new ArrayList<>();
        long aAcc = 0;
        for (int i = 0; i < aBuf.size(); i++) {
            StreamingSample s = aBuf.get(i);
            long after = aAcc + s.getDuration();
            if (!force && toMs(after, a.getTimescale()) > cutMs) break;
            aSel.add(s);
            aAcc = after;
        }
        if (aSel.isEmpty()) return;

        // baseMediaDecodeTime for both tracks: DO NOT add any drop; we don't drop here anyway
        long vBase = vStart;
        long aBase = aStart;

        // build moof (2×traf) and compute data_offset in truns
        MovieFragmentBox moof = new MovieFragmentBox();
        MovieFragmentHeaderBox mfhd = new MovieFragmentHeaderBox();
        mfhd.setSequenceNumber(sequenceNumber);
        moof.addBox(mfhd);

        createTraf(v, moof, vSel, vBase);
        createTraf(a, moof, aSel, aBase);

        List<TrackRunBox> truns = moof.getTrackRunBoxes();
        for (TrackRunBox tr : truns) tr.setDataOffset(1); // temporary

        long moofSize = moof.getSize();
        int firstPayloadOffset = (int) (8 + moofSize); // 8 = mdat header
        long vBytes = sumBytes(vSel);

        if (!truns.isEmpty()) truns.get(0).setDataOffset(firstPayloadOffset);                   // video trun
        if (truns.size() > 1) truns.get(1).setDataOffset((int) (firstPayloadOffset + vBytes)); // audio trun

        // mdat and write
        Box mdat = createMdat(vSel, aSel);
        writeFragment(moof, mdat);
        sequenceNumber++;

        // advance per track by the durations actually written
        long vUsed = sumDur(vSel);
        long aUsed = sumDur(aSel);
        nextFragmentStartTs.put(v, vStart + vUsed);
        nextFragmentStartTs.put(a, aStart + aUsed);

        // evict consumed from buffers
        int vConsume = vSel.size();
        if (vConsume > 0 && vConsume <= vBuf.size()) vBuf.subList(0, vConsume).clear();

        int aConsume = aSel.size();
        if (aConsume > 0 && aConsume <= aBuf.size()) aBuf.subList(0, aConsume).clear();

        // callback duration from exact ticks
        if (outputCallback != null) {
            long durMs = Math.min((vUsed * 1000) / v.getTimescale(), (aUsed * 1000) / a.getTimescale());
            outputCallback.onSegmentReady(v, durMs, false, false);
        }
    }

    private void createTraf(StreamingTrack track, MovieFragmentBox moof, List<StreamingSample> samples, long baseTs) {
        TrackFragmentBox traf = new TrackFragmentBox();
        moof.addBox(traf);
        createTfhd(track, traf);
        createTfdt(track, traf, baseTs);
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
        trun.setSampleCompositionTimeOffsetPresent(false); // no B-frames
        trun.setSampleFlagsPresent(true); // per-sample flags
        trun.setDataOffsetPresent(true);  // IMPORTANT: data_offset is present when we set it later

        List<TrackRunBox.Entry> entries = new ArrayList<TrackRunBox.Entry>(samples.size());
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
        f.setSampleDependsOn(key ? 2 : 1);   // 2 = does not depend on others; 1 = depends on others
        f.setSampleIsDifferenceSample(!key); // 0 for sync, 1 for non-sync
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

    // ===== Footer (optional) =====
    protected Box[] createFooter() { return new Box[0]; }

    // ===== Helpers =====

    private StreamingTrack findVideoTrack() {
        for (StreamingTrack t : source) {
            String n = t.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (n.contains("h264") || n.contains("avc") || n.contains("video")) return t;
        }
        return null;
    }

    private StreamingTrack findAudioTrack() {
        for (StreamingTrack t : source) {
            String n = t.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (n.contains("aac") || n.contains("audio") || n.contains("mp4a")) return t;
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

    private long toMs(long ts, long timescale) {
        return (long) ((ts * 1000.0) / timescale);
    }

    private long scaleTs(long ts, long fromScale, long toScale) {
        return fromScale == toScale ? ts : (long) Math.floor((ts * (double) toScale) / (double) fromScale + 0.5);
    }

    private boolean isKeyframeSample(StreamingSample s) {
        SampleFlagsSampleExtension f = s.getSampleExtension(SampleFlagsSampleExtension.class);
        return f == null || f.isSyncSample();
    }
}
