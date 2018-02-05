package com.cldellow.manu.format;

import me.lemire.integercompression.IntWrapper;
import me.lemire.integercompression.differential.IntegratedIntegerCODEC;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class ManuReader implements Reader {
    private final short rowListSize;
    private final int nullValue;
    private final int numDatapoints;
    private final Interval interval;
    private final int recordOffset;
    private final int numRecords;
    private final String[] fieldNames;
    private final FieldType[] fieldTypes;
    private final ManuRecordIterator records;
    private final int numFields;
    private final DateTime from;
    private final DateTime to;

    private final String fileName;
    private final long rowListOffset;

    public ManuReader(String fileName) throws FileNotFoundException, IOException, NotManuException {
        this.fileName = fileName;
        RandomAccessFile raf = new RandomAccessFile(fileName, "r");
        FileChannel channel = raf.getChannel();
        MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length());

        byte[] magicPreamble = new byte[]{'M', 'A', 'N', 'U', 0, (byte) Common.getVersion()};
        for (int i = 0; i < magicPreamble.length; i++)
            if (!buffer.hasRemaining() || buffer.get() != magicPreamble[i])
                throw new NotManuException();

        rowListSize = buffer.getShort();
        nullValue = buffer.getInt();
        long epochMs = buffer.getLong();
        numDatapoints = buffer.getInt();
        interval = Interval.valueOf(buffer.get());

        from = new DateTime(epochMs, DateTimeZone.UTC);
        to = interval.add(from, numDatapoints);

        recordOffset = buffer.getInt();
        numRecords = buffer.getInt();
        numFields = buffer.get();
        fieldTypes = new FieldType[numFields];
        fieldNames = new String[numFields];

        for (int i = 0; i < numFields; i++) {
            fieldTypes[i] = FieldType.valueOf(buffer.get());
        }
        for (int i = 0; i < numFields; i++) {
            int len = buffer.get();
            byte[] utf = new byte[len];
            buffer.get(utf, 0, len);
            fieldNames[i] = new String(utf, "UTF-8");
        }
        rowListOffset = buffer.getInt();
        channel.close();
        records = new ManuRecordIterator(0, 0);
    }

    public Interval getInterval() { return interval; }
    public int getNullValue() { return nullValue; }
    public int getNumRecords() { return numRecords; }
    public int getNumFields() { return numFields; }
    public int getRecordOffset() { return recordOffset; }
    public int getNumDatapoints() { return numDatapoints; }
    public short getRowListSize() { return rowListSize; }
    public String getFieldName(int i) { return fieldNames[i]; }
    public FieldType getFieldType(int i) { return fieldTypes[i]; }
    public RecordIterator getRecords() { return records; }
    public String getFileName() { return fileName; }
    public DateTime getFrom() { return from; }
    public DateTime getTo() { return to; }

    public Record get(int id) throws FileNotFoundException, IOException {
        ManuRecordIterator it = new ManuRecordIterator(
                id - recordOffset,
                (id - recordOffset) / rowListSize
        );

        try {
            Record rv = it.next();
            // Due to sparse records, the record we found may not actually be the record we wanted.
            if(rv.getId() == id)
                return rv;
            return null;
        } catch (NoSuchElementException nsee) {
            if(id < numRecords)
                return null;
            throw nsee;
        } finally {
            it.close();
        }
    }

    /*
    private String getSummary() {
        String fieldTypesStr = "";
        String fieldNamesStr = "";
        for(int i = 0; i < numFields; i++) {
            fieldTypesStr += fieldTypes[i];
            fieldNamesStr += fieldNames[i];
            if(i != numFields - 1) {
                fieldTypesStr += ", ";
                fieldNamesStr += ", ";
            }
        }
        return "epochMs=" + epochMs + ", numDatapoints=" + numDatapoints + ", interval=" + interval + "\n" +
                "recordOffset=" + recordOffset + ", numRecords=" + numRecords + ", numFields=" + numFields + "\n" +
                "fieldTypes=" + fieldTypesStr + ", fieldNames=" + fieldNamesStr + ", rowListOffset=" + rowListOffset;
    }
    */

    public class ManuRecordIterator implements RecordIterator {
        private final RandomAccessFile raf;
        private final FileChannel channel;
        private final MappedByteBuffer buffer;
        private IntegratedIntegerCODEC codec = Common.getRowListCodec();
        private int currentRecord;
        private int currentRowList;
        private int[] rowOffsets = null;
        // we re-use this array; but use rowOffsets as the source of truth for
        // availability of more data.
        private int recordsInThisRowList = 0;
        private int[] _rowOffsets = new int[rowListSize];
        private int[] _codedRowOffsets = new int[rowListSize * 4 + 32];
        private int[] rv = new int[numDatapoints];
        private byte[] tmp = new byte[4 * (numDatapoints + 256)];
        private boolean advancedToNext;
        private Record nextRecord = null;
        private boolean hasNext = false;

        // This points at the start of the next rowlist. It's used when inferring the
        // field length of the last field of the last record of the current rowlist.
        private int nextRowListStart = 0;

        ManuRecordIterator(int currentRecord, int currentRowList) throws FileNotFoundException, IOException {
            this.currentRecord = currentRecord;
            this.currentRowList = currentRowList;

            raf = new RandomAccessFile(fileName, "r");
            channel = raf.getChannel();
            buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length());
            advancedToNext = false;
        }

        public void close() throws IOException {
            channel.close();
        }

        private void advanceToNext() {
            if (advancedToNext)
                return;

            hasNext = currentRecord < numRecords;


            while (hasNext && !advancedToNext) {
                hasNext = currentRecord < numRecords;

                // Do we need to fetch a new rowlist?
                if (rowOffsets == null) {
                    buffer.position((int) (rowListOffset + 4 * currentRowList));
                    int rowListStart = buffer.getInt();
                    nextRowListStart = rowListStart;
                    buffer.position(rowListStart);

                    short len = buffer.getShort();
                    IntBuffer intBuffer = buffer.asIntBuffer();
                    intBuffer.get(_codedRowOffsets, 0, len);
                    IntWrapper outLen = new IntWrapper(0);
                    codec.uncompress(_codedRowOffsets, new IntWrapper(0), len, _rowOffsets, outLen);
                    if (outLen.get() + (currentRecord - currentRecord % rowListSize) > numRecords)
                        throw new IllegalArgumentException("more records found than expected");

                    if (outLen.get() != rowListSize && outLen.get() + (currentRecord - currentRecord % rowListSize) != numRecords)
                        throw new IllegalArgumentException(
                                String.format("incomplete rowlist found; had %d records, expected %d", outLen.get(), rowListSize));

                    recordsInThisRowList = outLen.get();
                    rowOffsets = _rowOffsets;

                    for (int i = 1; i < recordsInThisRowList; i++) {
                        rowOffsets[i] += rowOffsets[i - 1];
                    }
                }

                Record record = null;

                boolean isNextRowListStart = rowOffsets[currentRecord % rowListSize] == nextRowListStart;
                boolean isNextRecord = currentRecord + 1 < numRecords &&
                        (currentRecord + 1) % rowListSize != 0 &&
                        rowOffsets[currentRecord % rowListSize] == rowOffsets[(currentRecord + 1) % rowListSize];

                if (!isNextRowListStart && !isNextRecord) {
                    int recordStart = rowOffsets[currentRecord % rowListSize];
                    int recordEnd = 0;
                    if ((currentRecord % rowListSize != rowListSize - 1) && currentRecord != numRecords - 1) {
                        recordEnd = rowOffsets[(currentRecord + 1) % rowListSize];
                    } else
                        recordEnd = nextRowListStart;

                    record = new EncodedRecord(recordOffset + currentRecord, buffer, recordStart, recordEnd, numFields, tmp, rv);
                }
                currentRecord++;

                if (currentRecord % rowListSize == 0) {
                    currentRowList++;
                    rowOffsets = null;
                }

                nextRecord = record;

                if (nextRecord != null)
                    advancedToNext = true;

            }
        }

        public boolean hasNext() {
            advanceToNext();
            return hasNext;
        }

        public Record next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            advancedToNext = false;
            Record rv = nextRecord;
            nextRecord = null;
            return rv;
        }

        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}