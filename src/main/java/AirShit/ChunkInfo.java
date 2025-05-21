package AirShit;

public class ChunkInfo {
    public final long offset;
    public final long length;

    public ChunkInfo(long offset, long length) {
        this.offset = offset;
        this.length = length;
    }

    @Override
    public String toString() {
        return "ChunkInfo{" +
               "offset=" + offset +
               ", length=" + length +
               '}';
    }
}