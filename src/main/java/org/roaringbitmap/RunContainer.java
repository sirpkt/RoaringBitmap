/*
 * (c) Daniel Lemire, Owen Kaser, Samy Chambi, Jon Alvarado, Rory Graves, Björn Sperber
 * Licensed under the Apache License, Version 2.0.
 */
package org.roaringbitmap;

import java.io.*;
import java.util.Arrays;
import java.util.Iterator;


/**
 * This container takes the form of runs of consecutive values (effectively,
 * run-length encoding).
 */
public class RunContainer extends Container implements Cloneable {
    private static final int DEFAULT_INIT_SIZE = 4;
    private static final boolean ENABLE_GALLOPING_AND = false;

    private short[] valueslength;// we interleave values and lengths, so 
    // that if you have the values 11,12,13,14,15, you store that as 11,4 where 4 means that beyond 11 itself, there are
    // 4 contiguous values that follows.
    // Other example: e.g., 1, 10, 20,0, 31,2 would be a concise representation of  1, 2, ..., 11, 20, 31, 32, 33

    int nbrruns = 0;// how many runs, this number should fit in 16 bits.

    private static final long serialVersionUID = 1L;

    private RunContainer(int nbrruns, short[] valueslength) {
        this.nbrruns = nbrruns;
        this.valueslength = Arrays.copyOf(valueslength, valueslength.length);
    }

    // needed for deserialization
    public RunContainer(short [] valueslength) {
        this(valueslength.length/2, valueslength);
    }

    // lower-level specialized implementations might be faster
    public RunContainer( ShortIterator sIt, int nbrRuns) {
        this.nbrruns = nbrRuns;
        valueslength = new short[ 2*nbrRuns];
        if (nbrRuns == 0) return;

        int prevVal = -2; 
        int runLen=0;
        int runCount=0;
        while (sIt.hasNext()) {
            int curVal = Util.toIntUnsigned(sIt.next());
            if (curVal == prevVal+1)
                ++runLen;
            else {
                if (runCount > 0)
                    setLength(runCount-1, (short) runLen); 
                setValue(runCount, (short) curVal);
                runLen=0;
                ++runCount;
            }
            prevVal = curVal;
        }
        setLength(runCount-1, (short) runLen);
    }
    
    // convert to bitmap or array *if needed*
    protected Container toEfficientContainer() { 
        int sizeAsRunContainer = RunContainer.serializedSizeInBytes(this.nbrruns);
        int sizeAsBitmapContainer = BitmapContainer.serializedSizeInBytes(0);
        int card = this.getCardinality();
        int sizeAsArrayContainer = ArrayContainer.serializedSizeInBytes(card);
        if(sizeAsRunContainer <= Math.min(sizeAsBitmapContainer, sizeAsArrayContainer))
            return this;
        if(card <= ArrayContainer.DEFAULT_MAX_SIZE) {
            ArrayContainer answer = new ArrayContainer(card);
            answer.cardinality=0;
            for (int rlepos=0; rlepos < this.nbrruns; ++rlepos) {
                int runStart = Util.toIntUnsigned(this.getValue(rlepos));
                int runEnd = runStart + Util.toIntUnsigned(this.getLength(rlepos));

                for (int runValue = runStart; runValue <= runEnd; ++runValue) {
                    answer.content[answer.cardinality++] = (short) runValue;
                }
            }
            return answer;
        }
        BitmapContainer answer = new BitmapContainer();
        for (int rlepos=0; rlepos < this.nbrruns; ++rlepos) {
            int start = Util.toIntUnsigned(this.getValue(rlepos));
            int end = Util.toIntUnsigned(this.getValue(rlepos)) + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
            Util.setBitmapRange(answer.bitmap, start, end); 
        }
        answer.cardinality = card;
        return answer;
    }

    /**
     * Convert the container to either a Bitmap or an Array Container, depending
     * on the cardinality.
     * @return new container
     */
    protected Container toBitmapOrArrayContainer() {
        int card = this.getCardinality();
        if(card <= ArrayContainer.DEFAULT_MAX_SIZE) {
            ArrayContainer answer = new ArrayContainer(card);
            answer.cardinality=0;
            for (int rlepos=0; rlepos < this.nbrruns; ++rlepos) {
                int runStart = Util.toIntUnsigned(this.getValue(rlepos));
                int runEnd = runStart + Util.toIntUnsigned(this.getLength(rlepos));

                for (int runValue = runStart; runValue <= runEnd; ++runValue) {
                    answer.content[answer.cardinality++] = (short) runValue;
                }
            }
            return answer;
        }
        BitmapContainer answer = new BitmapContainer();
        for (int rlepos=0; rlepos < this.nbrruns; ++rlepos) {
            int start = Util.toIntUnsigned(this.getValue(rlepos));
            int end = Util.toIntUnsigned(this.getValue(rlepos)) + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
            Util.setBitmapRange(answer.bitmap, start, end); 
        }
        answer.cardinality = card;
        return answer;
    }

    // force conversion to bitmap irrespective of cardinality, result is not a valid container
    // this is potentially unsafe, use at your own risks
    protected BitmapContainer toTemporaryBitmap() {
        BitmapContainer answer = new BitmapContainer();
        for (int rlepos=0; rlepos < this.nbrruns; ++rlepos) {
            int start = Util.toIntUnsigned(this.getValue(rlepos));
            int end = Util.toIntUnsigned(this.getValue(rlepos)) + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
            Util.setBitmapRange(answer.bitmap, start, end); 
        }
        return answer;
    }

    /** 
     *  Convert to Array or Bitmap container if the serialized form would be shorter
     */

    @Override
    public Container runOptimize() {
        int currentSize = serializedSizeInBytes();
        int card = getCardinality(); 
        if (card <= ArrayContainer.DEFAULT_MAX_SIZE) {
            if (currentSize > ArrayContainer.serializedSizeInBytes(card))
                return toBitmapOrArrayContainer();
        }
        else if (currentSize > BitmapContainer.serializedSizeInBytes(card)) {
            return toBitmapOrArrayContainer();
        }
        return this;
    }

    private void increaseCapacity() {
        int newCapacity = (valueslength.length == 0) ? DEFAULT_INIT_SIZE : valueslength.length < 64 ? valueslength.length * 2
                : valueslength.length < 1024 ? valueslength.length * 3 / 2
                        : valueslength.length * 5 / 4;
        short[] nv = new short[newCapacity];
        System.arraycopy(valueslength, 0, nv, 0, 2 * nbrruns);
        valueslength = nv;
    }

    /**
     * Create a container with default capacity
     */
    public RunContainer() {
        this(DEFAULT_INIT_SIZE);
    }

    /**
     * Create an array container with specified capacity
     *
     * @param capacity The capacity of the container
     */
    public RunContainer(final int capacity) {
        valueslength = new short[2 * capacity];
    }


    @Override
    public Iterator<Short> iterator() {
        final ShortIterator i  = getShortIterator();
        return new Iterator<Short>() {

            @Override
            public boolean hasNext() {
                return  i.hasNext();
            }

            @Override
            public Short next() {
                return i.next();
            }

            @Override
            public void remove() {
                i.remove();
            }
        };

    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        serialize(out);

    }

    @Override
    public void readExternal(ObjectInput in) throws IOException,
    ClassNotFoundException {
        deserialize(in);
    }

    @Override
    public Container flip(short x) {
        if(this.contains(x))
            return this.remove(x);
        else return this.add(x);
    }

    @Override
    public Container add(short k) {
        int index = unsignedInterleavedBinarySearch(valueslength, 0, nbrruns, k);
        if(index >= 0) return this;// already there
        index = - index - 2;// points to preceding value, possibly -1
        if(index >= 0) {// possible match
            int offset = Util.toIntUnsigned(k) - Util.toIntUnsigned(getValue(index));
            int le =     Util.toIntUnsigned(getLength(index)); 
            if(offset <= le) return this;
            if(offset == le + 1) {
                // we may need to fuse
                if(index + 1 < nbrruns) {
                    if(Util.toIntUnsigned(getValue(index + 1))  == Util.toIntUnsigned(k) + 1) {
                        // indeed fusion is needed
                        setLength(index, (short) (getValue(index + 1) + getLength(index + 1) - getValue(index)));
                        recoverRoomAtIndex(index + 1);
                        return this;
                    }
                }
                incrementLength(index);
                return this;
            }
            if(index + 1 < nbrruns) {
                // we may need to fuse
                if(Util.toIntUnsigned(getValue(index + 1))  == Util.toIntUnsigned(k) + 1) {
                    // indeed fusion is needed
                    setValue(index+1, k);
                    setLength(index+1, (short) (getLength(index + 1) + 1));
                    return this;
                }
            }
        }
        if(index == -1) {
            // we may need to extend the first run
            if(0 < nbrruns) {
                if(getValue(0)  == k + 1) {
                    incrementLength(0);
                    decrementValue(0);
                    return this;
                }
            }
        }
        makeRoomAtIndex(index + 1);
        setValue(index + 1, k);
        setLength(index + 1, (short) 0);
        return this;
    }

    @Override
    public Container add(int begin, int end) {
        RunContainer rc = (RunContainer) clone();
        return rc.iadd(begin, end);
    }

    @Override
    public Container and(ArrayContainer x) {
        ArrayContainer ac = new ArrayContainer(x.cardinality);
        if(this.nbrruns == 0) return ac;
        int rlepos = 0;
        int arraypos = 0;

        int rleval = Util.toIntUnsigned(this.getValue(rlepos));
        int rlelength = Util.toIntUnsigned(this.getLength(rlepos));        
        while(arraypos < x.cardinality)  {
            int arrayval = Util.toIntUnsigned(x.content[arraypos]);
            while(rleval + rlelength < arrayval) {// this will frequently be false
                ++rlepos;
                if(rlepos == this.nbrruns) {
                    return ac;// we are done
                }
                rleval = Util.toIntUnsigned(this.getValue(rlepos));
                rlelength = Util.toIntUnsigned(this.getLength(rlepos));
            }
            if(rleval > arrayval)  {
                arraypos = Util.advanceUntil(x.content,arraypos,x.cardinality,this.getValue(rlepos));
            } else {
                ac.content[ac.cardinality] = (short) arrayval;
                ac.cardinality++;
                arraypos++;
            }
        }
        return ac;
    }



    @Override
    public Container and(BitmapContainer x) {
        // could be implemented as return toBitmapOrArrayContainer().iand(x);
        int card = this.getCardinality();
        if (card <=  ArrayContainer.DEFAULT_MAX_SIZE) {
            // result can only be an array (assuming that we never make a RunContainer)
            ArrayContainer answer = new ArrayContainer(card);
            answer.cardinality=0;
            for (int rlepos=0; rlepos < this.nbrruns; ++rlepos) {
                int runStart = Util.toIntUnsigned(this.getValue(rlepos));
                int runEnd = runStart + Util.toIntUnsigned(this.getLength(rlepos));
                for (int runValue = runStart; runValue <= runEnd; ++runValue) {
                    if ( x.contains((short) runValue)) {// it looks like contains() should be cheap enough if accessed sequentially
                        answer.content[answer.cardinality++] = (short) runValue;
                    }
                }
            }
            return answer;
        }
        // we expect the answer to be a bitmap (if we are lucky)
        BitmapContainer answer = x.clone();
        int start = 0;
        for(int rlepos = 0; rlepos < this.nbrruns; ++rlepos ) {
            int end = Util.toIntUnsigned(this.getValue(rlepos));
            Util.resetBitmapRange(answer.bitmap, start, end);  // had been x.bitmap
            start = Util.toIntUnsigned(this.getValue(rlepos)) + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
        }
        Util.resetBitmapRange(answer.bitmap, start, Util.maxLowBitAsInteger() + 1);   // had been x.bitmap
        answer.computeCardinality();
        if(answer.getCardinality() > ArrayContainer.DEFAULT_MAX_SIZE)
            return answer;
        else return answer.toArrayContainer();
    }


    @Override
    public Container andNot(BitmapContainer x) {
        //could be implemented as toTemporaryBitmap().iandNot(x);
        int card = this.getCardinality();
        if (card <=  ArrayContainer.DEFAULT_MAX_SIZE) {
            // result can only be an array (assuming that we never make a RunContainer)
            ArrayContainer answer = new ArrayContainer(card);
            answer.cardinality=0;
            for (int rlepos=0; rlepos < this.nbrruns; ++rlepos) {
                int runStart = Util.toIntUnsigned(this.getValue(rlepos));
                int runEnd = runStart + Util.toIntUnsigned(this.getLength(rlepos));
                for (int runValue = runStart; runValue <= runEnd; ++runValue) {
                    if ( ! x.contains((short) runValue)) {// it looks like contains() should be cheap enough if accessed sequentially
                        answer.content[answer.cardinality++] = (short) runValue;
                    }
                }
            }
            return answer;
        }
        // we expect the answer to be a bitmap (if we are lucky)
        BitmapContainer answer = x.clone();
        int lastPos = 0;
        for (int rlepos = 0; rlepos < this.nbrruns; ++rlepos) {
            int start = Util.toIntUnsigned(this.getValue(rlepos));
            int end = Util.toIntUnsigned(this.getValue(rlepos)) + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
            Util.resetBitmapRange(answer.bitmap, lastPos, start); 
            Util.flipBitmapRange(answer.bitmap, start, end);
            lastPos = end;
        }
        Util.resetBitmapRange(answer.bitmap, lastPos, answer.bitmap.length*64);
        answer.computeCardinality();
        if (answer.getCardinality() > ArrayContainer.DEFAULT_MAX_SIZE)
            return answer;
        else
            return answer.toArrayContainer();
    }


    @Override
    public Container andNot(ArrayContainer x) {
        // this is lazy, but is this wise?
        return toBitmapOrArrayContainer().iandNot(x);
    }



    @Override
    public void clear() {
        nbrruns = 0;
    }

    @Override
    public Container clone() {
        return new RunContainer(nbrruns, valueslength);
    }

    @Override
    public boolean contains(short x) {
        int index = unsignedInterleavedBinarySearch(valueslength, 0, nbrruns, x);
        if(index >= 0) return true;
        index = - index - 2; // points to preceding value, possibly -1
        if (index != -1)  {// possible match
            int offset = Util.toIntUnsigned(x) - Util.toIntUnsigned(getValue(index));
            int le =     Util.toIntUnsigned(getLength(index)); 
            if(offset <= le) return true;
        }
        return false;
    }

    @Override
    public void deserialize(DataInput in) throws IOException {
        nbrruns = Short.reverseBytes(in.readShort());
        if(valueslength.length < 2 * nbrruns)
            valueslength = new short[2 * nbrruns];
        for (int k = 0; k < 2 * nbrruns; ++k) {
            this.valueslength[k] = Short.reverseBytes(in.readShort());
        }
    }

    @Override
    public void fillLeastSignificant16bits(int[] x, int i, int mask) {
        int pos = i;
        for (int k = 0; k < this.nbrruns; ++k) {
            final int limit = Util.toIntUnsigned(this.getLength(k));
            final int base = Util.toIntUnsigned(this.getValue(k));
            for(int le = 0; le <= limit; ++le) {
                x[pos++] = (base + le) | mask;
            }
        }
    }

    @Override
    protected int getArraySizeInBytes() {
        return 2+4*this.nbrruns;  // "array" includes its size
    }

    @Override
    public int getCardinality() {
        /**
         * Daniel has a concern with this part of the
         * code. Lots of code may assume that we can query
         * the cardinality in constant-time. That is the case
         * with other containers. So it might be worth
         * the effort to have a pre-computed cardinality somewhere.
         * The only downsides are: (1) slight increase in memory
         * usage (probably negligible) (2) slower updates
         * (this container type is probably not the subject of
         * frequent updates).
         * 
         * On the other hand, storing a precomputed cardinality
         * separately is maybe wasteful and introduces extra
         * code. 
         * 
         * Current verdict: keep things as they are, but be
         * aware that  getCardinality might become a bottleneck.
         */
        int sum = 0;
        for(int k = 0; k < nbrruns; ++k)
            sum = sum + Util.toIntUnsigned(getLength(k)) + 1;
        return sum;
    }

    @Override
    public ShortIterator getShortIterator() {
        return new RunContainerShortIterator(this);
    }

    @Override
    public ShortIterator getReverseShortIterator() {
        return new ReverseRunContainerShortIterator(this);
    }

    @Override
    public int getSizeInBytes() {
        return this.nbrruns * 4 + 4;
    }

    @Override
    public Container iand(ArrayContainer x) {
        return and(x);
    }

    @Override
    public Container iand(BitmapContainer x) {
        return and(x);
    }

    @Override
    public Container iandNot(ArrayContainer x) {
        return andNot(x);
    }

    @Override
    public Container iandNot(BitmapContainer x) {
        return andNot(x);
    }

    @Override
    public Container inot(int rangeStart, int rangeEnd) {
        if (rangeEnd <= rangeStart) return this;  
        else
            return not( rangeStart, rangeEnd);  // TODO: inplace option?
    }

    @Override
    public Container ior(ArrayContainer x) {
        return or(x);
    }

    @Override
    public Container ior(BitmapContainer x) {
        return or(x);
    }

    @Override
    public Container ixor(ArrayContainer x) {
        return xor(x);
    }

    @Override
    public Container ixor(BitmapContainer x) {
        return xor(x);
    }


    // handles any required fusion, assumes space available
    private int addRun(int outputRlePos, int runStart, int lastRunElement) {
        int runLength = lastRunElement - runStart;
        // check whether fusion is required
        if (outputRlePos > 0) { // there is a previous run
            int prevRunStart = Util.toIntUnsigned(this.getValue(outputRlePos-1));
            int prevRunEnd = prevRunStart + Util.toIntUnsigned(this.getLength(outputRlePos-1));
            if (prevRunEnd+1 == runStart) { // we must fuse
                int newRunEnd = prevRunEnd+(1+runLength);
                int newRunLen = newRunEnd-prevRunStart;
                setLength(outputRlePos-1, (short) newRunLen);
                return outputRlePos; // do not advance, nbrruns unchanged
            }
        }
        // cases without fusion
        setValue(outputRlePos, (short) runStart);
        setLength(outputRlePos, (short) runLength);
        nbrruns=outputRlePos+1;

        return  ++outputRlePos;
    }


    @Override
    public Container not(int rangeStart, int rangeEnd) {

        // This code is a pain to test...

        if (rangeEnd <= rangeStart) return this.clone();

        // A container that is best stored as a run container
        // is frequently going to have its "inot" also best stored
        // as a run container. This would violate an implicit
        // "results are array or bitmaps only" rule, if we had one.

        // The number of runs in the result can be bounded
        // It's not clear, but I'm guessing the bound is a max increase of 1
        // array bounds checking will kick in if this is wrong
        RunContainer ans = new RunContainer(nbrruns+1);

        // annoying special case: there is no run.  Then the range becomes the run.
        if (nbrruns == 0) {
            ans.addRun(0, rangeStart, rangeEnd-1);
            return ans;
        }

        int outputRlepos = 0;
        int rlepos;
        // copy all runs before the range.
        for (rlepos=0; rlepos < nbrruns; ++rlepos) {
            int runStart = Util.toIntUnsigned(this.getValue(rlepos));
            int runEnd = runStart + Util.toIntUnsigned(this.getLength(rlepos));

            if (runEnd >=  rangeStart) break;
            outputRlepos = ans.addRun(outputRlepos, runStart, runEnd);
        }

        if (rlepos < nbrruns) {
            // there may be a run that starts before the range but
            //  intersects with the range; copy the part before the intersection.

            int runStart = Util.toIntUnsigned(this.getValue(rlepos));
            if (runStart < rangeStart) {
                outputRlepos = ans.addRun(outputRlepos, runStart, rangeStart-1);
                // do not increase rlepos, as the rest of this run needs to be handled
            }
        }


        for (; rlepos < nbrruns; ++rlepos) {
            int runStart = Util.toIntUnsigned(this.getValue(rlepos));
            int runEnd = runStart + Util.toIntUnsigned(this.getLength(rlepos));

            if (runStart >= rangeEnd) break; // handle these next

            int endOfPriorRun;
            if (rlepos == 0)
                endOfPriorRun=-1;
            else
                endOfPriorRun = Util.toIntUnsigned(this.getValue(rlepos-1)) + Util.toIntUnsigned(this.getLength(rlepos-1));

            // but only gap locations after the start of the range count.
            int startOfInterRunGap = Math.max(endOfPriorRun+1, rangeStart);

            int lastOfInterRunGap = Math.min(runStart-1, rangeEnd-1);            
            // and only gap locations before (strictly) the rangeEnd count

            if (lastOfInterRunGap >= startOfInterRunGap)
                outputRlepos = ans.addRun(outputRlepos, startOfInterRunGap, lastOfInterRunGap);
            // else we had a run that started before the range, and thus no gap


            // there can be a run that begins before the end of the range but ends afterward.
            // the portion that extends beyond the range needs to be copied.
            if (runEnd >= rangeEnd) // recall: runEnd is inclusive, rangeEnd is exclusive
                outputRlepos = ans.addRun(outputRlepos, rangeEnd, runEnd);
        }

        // if the kth run is entirely within the range and the k+1st entirely outside,
        // then we need to pick up the gap between the end of the kth run and the range's end
        if (rlepos > 0) {
            int endOfPriorRun = Util.toIntUnsigned(this.getValue(rlepos-1)) + Util.toIntUnsigned(this.getLength(rlepos-1));
            if (rlepos < nbrruns) {
                int  runStart= Util.toIntUnsigned(this.getValue(rlepos));
                if (endOfPriorRun >= rangeStart &&
                        endOfPriorRun < rangeEnd-1 && // there is a nonempty gap
                        runStart >= rangeEnd)
                    outputRlepos = ans.addRun(outputRlepos, endOfPriorRun+1, rangeEnd-1);
            }
            // else is handled by special processing for "last run ends before the range"
        }


        // handle case where range occurs before first run
        if (rlepos == 0)  {
            outputRlepos = ans.addRun(outputRlepos, rangeStart, rangeEnd-1);
        }


        // any more runs are totally after the range, copy them
        for (; rlepos < nbrruns; ++rlepos) {
            int runStart = Util.toIntUnsigned(this.getValue(rlepos));
            int runEnd = runStart + Util.toIntUnsigned(this.getLength(rlepos));

            outputRlepos = ans.addRun(outputRlepos, runStart, runEnd);
        }

        // if the last run ends before the range, special processing needed.
        int lastRunEnd =   Util.toIntUnsigned(this.getValue(nbrruns-1)) + 
                Util.toIntUnsigned(this.getLength(nbrruns-1));

        if (lastRunEnd < rangeEnd-1) {
            int startOfFlippedRun = Math.max(rangeStart, lastRunEnd+1);
            outputRlepos = ans.addRun(outputRlepos, startOfFlippedRun, rangeEnd-1);
        }
        return ans;
        // _could_ do a size check here and convert to
        // array or bitmap (implying it was probably silly
        // for the original container to be a Runcontainer..)
    }

    @Override
    public Container or(ArrayContainer x) {
        return toBitmapOrArrayContainer().ior(x);
        //return x.or(getShortIterator());   // performance may not be great, depending on iterator overheads...
    }

    @Override
    public Container or(BitmapContainer x) {
        // could be implemented as  return toTemporaryBitmap().ior(x);
        BitmapContainer answer = x.clone();
        for(int rlepos = 0; rlepos < this.nbrruns; ++rlepos ) {
            int start = Util.toIntUnsigned(this.getValue(rlepos));
            int end = Util.toIntUnsigned(this.getValue(rlepos)) + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
            Util.setBitmapRange(answer.bitmap, start, end);
        }
        answer.computeCardinality();
        return answer;
    }

    @Override
    public Container remove(short x) {
        int index = unsignedInterleavedBinarySearch(valueslength, 0, nbrruns, x);
        if(index >= 0) {
            int le =  Util.toIntUnsigned(getLength(index));
            if(le == 0) {
                recoverRoomAtIndex(index);
            } else {
                incrementValue(index);
                decrementLength(index);
            }
            return this;// already there
        }
        index = - index - 2;// points to preceding value, possibly -1
        if(index >= 0) {// possible match
            int offset = Util.toIntUnsigned(x) - Util.toIntUnsigned(getValue(index));
            int le =     Util.toIntUnsigned(getLength(index)); 
            if(offset < le) {
                // need to break in two
                this.setLength(index, (short) (offset - 1));
                // need to insert
                int newvalue = Util.toIntUnsigned(x) + 1;
                int newlength = le - offset - 1;
                makeRoomAtIndex(index+1);
                this.setValue(index+1, (short) newvalue);
                this.setLength(index+1, (short) newlength);
            } else if(offset == le) {
                decrementLength(index);
            }
        }
        // no match
        return this;
    }

    @Override
    public void serialize(DataOutput out) throws IOException {
        writeArray(out);
    }

    @Override
    public int serializedSizeInBytes() {
        return serializedSizeInBytes(nbrruns);
    }

    public static int serializedSizeInBytes( int numberOfRuns) {
        return 2 + 2 * 2 * numberOfRuns;  // each run requires 2 2-byte entries.
    }

    @Override
    public void trim() {
        if(valueslength.length == 2 * nbrruns) return;
        valueslength = Arrays.copyOf(valueslength, 2 * nbrruns);
    }

    @Override
    protected void writeArray(DataOutput out) throws IOException {
        out.writeShort(Short.reverseBytes((short) this.nbrruns));
        for (int k = 0; k < 2 * this.nbrruns; ++k) {
            out.writeShort(Short.reverseBytes(this.valueslength[k]));
        }
    }

    @Override
    public Container xor(ArrayContainer x) {
        return toBitmapOrArrayContainer().ixor(x);
        //  return x.xor(getShortIterator());   // performance may not be great, depending on iterator overheads...
    }

    @Override
    public Container xor(BitmapContainer x) {
        // could be implemented as return toTemporaryBitmap().ixor(x);
        BitmapContainer answer = x.clone();
        for (int rlepos = 0; rlepos < this.nbrruns; ++rlepos) {
            int start = Util.toIntUnsigned(this.getValue(rlepos));
            int end = Util.toIntUnsigned(this.getValue(rlepos)) + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
            Util.flipBitmapRange(answer.bitmap, start, end);
        }
        answer.computeCardinality();
        if (answer.getCardinality() > ArrayContainer.DEFAULT_MAX_SIZE)
            return answer;
        else
            return answer.toArrayContainer();
    }

    @Override
    public int rank(short lowbits) {
        int x = Util.toIntUnsigned(lowbits);
        int answer = 0;
        for (int k = 0; k < this.nbrruns; ++k) {
            int value = Util.toIntUnsigned(getValue(k));
            int length = Util.toIntUnsigned(getLength(k));
            if (x < value) {
                return answer;
            } else if (value + length + 1 >= x) {
                return answer + x - value + 1;
            }
            answer += length + 1;
        }
        return answer;
    }

    @Override
    public short select(int j) {
        int offset = 0;
        for (int k = 0; k < this.nbrruns; ++k) {
            int nextOffset = offset + Util.toIntUnsigned(getLength(k)) + 1;
            if(nextOffset > j) {
                return (short)(getValue(k) + (j - offset));
            }
            offset = nextOffset;
        }
        throw new IllegalArgumentException("Cannot select "+j+" since cardinality is "+getCardinality());        
    }

    @Override
    public Container limit(int maxcardinality) {
        if(maxcardinality >= getCardinality()) {
            return clone();
        }

        int r;
        int cardinality = 0;
        for (r = 1; r <= this.nbrruns; ++r) {
            cardinality += Util.toIntUnsigned(getLength(r)) + 1;
            if (maxcardinality <= cardinality) {
                break;
            }
        }
        RunContainer rc = new RunContainer(r, Arrays.copyOf(valueslength, 2*r));
        // TODO: OFK: this ends up doing a double array copy.
        rc.setLength(r - 1, (short) (Util.toIntUnsigned(rc.getLength(r - 1)) - cardinality + maxcardinality));
        return rc;
    }

    @Override
    public Container iadd(int begin, int end) {
        if((begin >= end) || (end > (1<<16))) {
            throw new IllegalArgumentException("Invalid range [" + begin + "," + end + ")");
        }

        if(begin == end-1) {
            add((short) begin);
            return this;
        }

        int bIndex = unsignedInterleavedBinarySearch(this.valueslength, 0, this.nbrruns, (short) begin);
        int eIndex = unsignedInterleavedBinarySearch(this.valueslength, 0, this.nbrruns, (short) (end-1));

        if(bIndex>=0 && eIndex>=0) {
            mergeValuesLength(bIndex, eIndex);
            return this;

        } else if(bIndex>=0 && eIndex<0) {
            eIndex = -eIndex - 2;

            if(canPrependValueLength(end-1, eIndex+1)) {
                mergeValuesLength(bIndex, eIndex+1);
                return this;
            }

            appendValueLength(end-1, eIndex);
            mergeValuesLength(bIndex, eIndex);
            return this;

        } else if(bIndex<0 && eIndex>=0) {
            bIndex = -bIndex - 2;

            if(bIndex>=0) {
                if(valueLengthContains(begin-1, bIndex)) {
                    mergeValuesLength(bIndex, eIndex);
                    return this;
                }
            }
            prependValueLength(begin, bIndex+1);
            mergeValuesLength(bIndex+1, eIndex);
            return this;

        } else {
            bIndex = -bIndex - 2;
            eIndex = -eIndex - 2;

            if(eIndex>=0) {
                if(bIndex>=0) {
                    if(!valueLengthContains(begin-1, bIndex)) {
                        if(bIndex==eIndex) {
                            if(canPrependValueLength(end-1, eIndex+1)) {
                                prependValueLength(begin, eIndex+1);
                                return this;
                            }
                            makeRoomAtIndex(eIndex+1);
                            setValue(eIndex+1, (short) begin);
                            setLength(eIndex+1, (short) (end - 1 - begin));
                            return this;

                        } else {
                            bIndex++;
                            prependValueLength(begin, bIndex);
                        }
                    }
                } else {
                    bIndex = 0;
                    prependValueLength(begin, bIndex);
                }

                if(canPrependValueLength(end-1, eIndex+1)) {
                    mergeValuesLength(bIndex, eIndex + 1);
                    return this;
                }

                appendValueLength(end-1, eIndex);
                mergeValuesLength(bIndex, eIndex);
                return this;

            } else {
                if(canPrependValueLength(end-1, 0)) {
                    prependValueLength(begin, 0);
                } else {
                    makeRoomAtIndex(0);
                    setValue(0, (short) begin);
                    setLength(0, (short) (end - 1 - begin));
                }
                return this;
            }
        }
    }

    @Override
    public Container iremove(int begin, int end) {
        if((begin >= end) || (end > (1<<16))) {
            throw new IllegalArgumentException("Invalid range [" + begin + "," + end + ")");
        }

        if(begin == end-1) {
            remove((short) begin);
            return this;
        }

        int bIndex = unsignedInterleavedBinarySearch(this.valueslength, 0, this.nbrruns, (short) begin);
        int eIndex = unsignedInterleavedBinarySearch(this.valueslength, 0, this.nbrruns, (short) (end-1));

        if(bIndex>=0) {
            if(eIndex<0) {
                eIndex = -eIndex - 2;
            }

            if(valueLengthContains(end, eIndex)) {
                initValueLength(end, eIndex);
                recoverRoomsInRange(bIndex-1, eIndex-1);
            } else {
                recoverRoomsInRange(bIndex-1, eIndex);
            }

        } else if(bIndex<0 && eIndex>=0) {
            bIndex = -bIndex - 2;

            if(bIndex >= 0) {
                if (valueLengthContains(begin, bIndex)) {
                    closeValueLength(begin - 1, bIndex);
                }
            }
            incrementValue(eIndex);
            decrementLength(eIndex);
            recoverRoomsInRange(bIndex, eIndex-1);

        } else {
            bIndex = -bIndex - 2;
            eIndex = -eIndex - 2;

            if(eIndex>=0) {
                if(bIndex>=0) {
                    if(bIndex==eIndex) {
                        if (valueLengthContains(begin, bIndex)) {
                            if (valueLengthContains(end, eIndex)) {
                                makeRoomAtIndex(bIndex);
                                closeValueLength(begin-1, bIndex);
                                initValueLength(end, bIndex+1);
                                return this;
                            }
                            closeValueLength(begin-1, bIndex);
                        }
                    } else {
                        if (valueLengthContains(begin, bIndex)) {
                            closeValueLength(begin - 1, bIndex);
                        }
                        if (valueLengthContains(end, eIndex)) {
                            initValueLength(end, eIndex);
                            eIndex--;
                        }
                        recoverRoomsInRange(bIndex, eIndex);
                    }

                } else {
                    if(valueLengthContains(end-1, eIndex)) {
                        initValueLength(end, eIndex);
                        recoverRoomsInRange(bIndex, eIndex - 1);
                    } else {
                        recoverRoomsInRange(bIndex, eIndex);
                    }
                }

            }

        }

        return this;
    }

    @Override
    public Container remove(int begin, int end) {
        RunContainer rc = (RunContainer) clone();
        return rc.iremove(begin, end);
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof RunContainer) {
            RunContainer srb = (RunContainer) o;
            if (srb.nbrruns != this.nbrruns)
                return false;
            for (int i = 0; i < nbrruns; ++i) {
                if (this.getValue(i) != srb.getValue(i))
                    return false;
                if (this.getLength(i) != srb.getLength(i))
                    return false;
            }
            return true;
        } else if(o instanceof Container) {
            if(((Container) o).getCardinality() != this.getCardinality())
                return false; // should be a frequent branch if they differ
            // next bit could be optimized if needed:
            ShortIterator me = this.getShortIterator();
            ShortIterator you = ((Container) o).getShortIterator();
            while(me.hasNext()) {
                if(me.next() != you.next())
                    return false;
            }
            return true;
        }
        return false;
    }


    protected static int unsignedInterleavedBinarySearch(final short[] array,
            final int begin, final int end, final short k) {
        int ikey = Util.toIntUnsigned(k);
        int low = begin;
        int high = end - 1;
        while (low <= high) {
            final int middleIndex = (low + high) >>> 1;
            final int middleValue = Util.toIntUnsigned(array[2 * middleIndex]);
            if (middleValue < ikey)
                low = middleIndex + 1;
            else if (middleValue > ikey)
                high = middleIndex - 1;
            else
                return middleIndex;
        }
        return -(low + 1);
    }

    short getValue(int index) {
        return valueslength[2*index];
    }

    short getLength(int index) {
        return valueslength[2*index + 1];
    }

    private void incrementLength(int index) {
        valueslength[2*index + 1]++;
    }

    private void incrementValue(int index) {
        valueslength[2*index]++;
    }

    private void decrementLength(int index) {
        valueslength[2*index + 1]--;
    }

    private void decrementValue(int index) {
        valueslength[2*index]--;
    }

    private void setLength(int index, short v) {
        setLength(valueslength, index, v);
    }

    private void setLength(short[] valueslength, int index, short v) {
        valueslength[2*index + 1] = v;
    }

    private void setValue(int index, short v) {
        setValue(valueslength, index, v);
    }

    private void setValue(short[] valueslength, int index, short v) {
        valueslength[2*index] = v;
    }

    private void makeRoomAtIndex(int index) {
        if (2 * (nbrruns+1) > valueslength.length) increaseCapacity();
        copyValuesLength(valueslength, index, valueslength, index + 1, nbrruns - index);
        nbrruns++;
    }

    private void recoverRoomAtIndex(int index) {
        copyValuesLength(valueslength, index + 1, valueslength, index, nbrruns - index - 1);
        nbrruns--;
    }

    // To recover rooms between begin(exclusive) and end(inclusive)
    private void recoverRoomsInRange(int begin, int end) {
        if (end + 1 < this.nbrruns) {
            copyValuesLength(this.valueslength, end + 1, this.valueslength, begin + 1, this.nbrruns - 1 - end);
        }
        this.nbrruns -= end - begin;
    }

    // To merge values length from begin(inclusive) to end(inclusive)
    private void mergeValuesLength(int begin, int end) {
        if(begin < end) {
            int bValue = Util.toIntUnsigned(getValue(begin));
            int eValue = Util.toIntUnsigned(getValue(end));
            int eLength = Util.toIntUnsigned(getLength(end));
            int newLength = eValue - bValue + eLength;
            setLength(begin, (short) newLength);
            recoverRoomsInRange(begin, end);
        }
    }

    // To check if a value length can be prepended with a given value
    private boolean canPrependValueLength(int value, int index) {
        if(index < this.nbrruns) {
            int nextValue = Util.toIntUnsigned(getValue(index));
            if(nextValue == value+1) {
                return true;
            }
        }
        return false;
    }

    // Prepend a value length with all values starting from a given value
    private void prependValueLength(int value, int index) {
        int initialValue = Util.toIntUnsigned(getValue(index));
        int length = Util.toIntUnsigned(getLength(index));
        setValue(index, (short) value);
        setLength(index, (short) (initialValue - value + length));
    }

    // Append a value length with all values until a given value
    private void appendValueLength(int value, int index) {
        int previousValue = Util.toIntUnsigned(getValue(index));
        int length = Util.toIntUnsigned(getLength(index));
        int offset = value - previousValue;
        if(offset>length) {
            setLength(index, (short) offset);
        }
    }

    // To check if a value length contains a given value
    private boolean valueLengthContains(int value, int index) {
        int initialValue = Util.toIntUnsigned(getValue(index));
        int length = Util.toIntUnsigned(getLength(index));

        if(value <= initialValue + length) {
            return true;
        }
        return false;
    }

    // To set the first value of a value length
    private void initValueLength(int value, int index) {
        int initialValue = Util.toIntUnsigned(getValue(index));
        int length = Util.toIntUnsigned(getLength(index));
        setValue(index, (short) (value));
        setLength(index, (short) (length - (value - initialValue)));
    }

    // To set the last value of a value length
    private void closeValueLength(int value, int index) {
        int initialValue = Util.toIntUnsigned(getValue(index));
        setLength(index, (short) (value - initialValue));
    }

    private void copyValuesLength(short[] src, int srcIndex, short[] dst, int dstIndex, int length) {
        System.arraycopy(src, 2*srcIndex, dst, 2*dstIndex, 2*length);
    }

    // used for estimates of whether to prefer Array or Bitmap container results
    // when combining two RunContainers

    /*private double getDensity() {
        int myCard = getCardinality();
        return ((double) myCard) / (1 << 16);
    }


    private final double ARRAY_MAX_DENSITY  = ( (double) ArrayContainer.DEFAULT_MAX_SIZE)  / (1<<16);
     */




    // If we care to depend on Java 8, and if the runtime cost
    // is reasonable, lambdas could encode how to handle
    // "first sequence only has item", "second sequence only has item"
    // or "both sequences have item".  Could also use function objects
    // with earlier Java.  It is worth microbenchmarking, maybe, but I
    // guessed that using operation codes might be more efficient -owen

    //private static final int OP_AND=0, OP_ANDNOT=1, OP_OR=2, OP_XOR=3;


    // borrowed this tuned-looking code from ArrayContainer.
    // except: DEFAULT_INIT_SIZE is private...borrowed current setting
    /*private short [] increaseCapacity(short [] content) {
        int newCapacity = (content.length == 0) ? 4 : content.length < 64 ? content.length * 2
                : content.length < 1024 ? content.length * 3 / 2
                : content.length * 5 / 4;
        // allow it to exceed DEFAULT_MAX_SIZE
        return Arrays.copyOf(content, newCapacity);
    }*/

    // generic merge algorithm, Array output.  Should be possible to
    // improve on it for AND and ANDNOT, at least.

    /*private Container operationArrayGuess(RunContainer x, int opcode) {
        short [] ansArray = new short[10]; 
        int card = 0;
        int thisHead, xHead; // -1 means end of run

        // hoping that iterator overhead can be largely optimized away, dunno...

        ShortIterator it = getShortIterator();  // rely on unsigned ordering
        ShortIterator xIt = x.getShortIterator();

        thisHead = (it.hasNext() ?  Util.toIntUnsigned(it.next()) : -1);
        xHead =  (xIt.hasNext() ?  Util.toIntUnsigned(xIt.next()) : -1);

        while (thisHead != -1 && xHead != -1) {

            if (thisHead > xHead) {
                // item present in x only: we want for OR and XOR only
                if (opcode == OP_OR|| opcode == OP_XOR) {
                    // emit item to array
                    if (card == ansArray.length) ansArray = increaseCapacity(ansArray);
                    ansArray[card++] = (short) xHead;
                }
                xHead =  (xIt.hasNext() ?  Util.toIntUnsigned(xIt.next()) : -1);
            }
            else if (thisHead < xHead) {
                // item present in this only.  We want for OR, XOR plus ANDNOT  (all except AND)
                if (opcode != OP_AND) {
                    // emit to array
                    if (card == ansArray.length) ansArray = increaseCapacity(ansArray);
                    ansArray[card++] = (short) thisHead;
                }
                thisHead = (it.hasNext() ?  Util.toIntUnsigned(it.next()) : -1);
            }
            else { // item is present in both x and this;   AND and OR should get it, but not XOR or ANDNOT
                if (opcode == OP_AND || opcode == OP_OR) {
                    // emit to array
                    if (card == ansArray.length) ansArray = increaseCapacity(ansArray);
                    ansArray[card++] = (short) thisHead;
                }
                thisHead = (it.hasNext() ?  Util.toIntUnsigned(it.next()) : -1);
                xHead =  (xIt.hasNext() ?  Util.toIntUnsigned(xIt.next()) : -1);
            }
        }

        // AND does not care if there are extra items in either 
        if (opcode != OP_AND) {

            // OR, ANDNOT, XOR all want extra items in this sequence
            while (thisHead != -1) {
                // emit to array
                if (card == ansArray.length) ansArray = increaseCapacity(ansArray);
                ansArray[card++] = (short) thisHead;
                thisHead = (it.hasNext() ?  Util.toIntUnsigned(it.next()) : -1);
            }

            // OR and XOR want extra items in x sequence
            if (opcode == OP_OR || opcode == OP_XOR)
                while (xHead != -1) {
                    // emit to array
                    if (card == ansArray.length) ansArray = increaseCapacity(ansArray);
                    ansArray[card++] = (short) xHead;
                    xHead =  (xIt.hasNext() ?  Util.toIntUnsigned(xIt.next()) : -1);
                } 
        }

        // double copy could be avoided if the private card-is-parameter constructor for ArrayContainer were protected rather than private.
        short [] content = Arrays.copyOf(ansArray, card);
        ArrayContainer ac = new ArrayContainer(content);
        if (card > ArrayContainer.DEFAULT_MAX_SIZE)
            return ac.toBitmapContainer();
        else
            return ac;
    }*/


    // generic merge algorithm, copy-paste for bitmap output
    /*private Container operationBitmapGuess(RunContainer x, int opcode) {
        BitmapContainer answer = new BitmapContainer();
        int thisHead, xHead; // -1 means end of run

        ShortIterator it = getShortIterator();  
        ShortIterator xIt = x.getShortIterator();

        thisHead = (it.hasNext() ?  Util.toIntUnsigned(it.next()) : -1);
        xHead =  (xIt.hasNext() ?  Util.toIntUnsigned(xIt.next()) : -1);

        while (thisHead != -1 && xHead != -1) {

            if (thisHead > xHead) {
                // item present in x only: we want for OR and XOR only
                if (opcode == OP_OR|| opcode == OP_XOR) 
                    answer.add((short) xHead);
                xHead =  (xIt.hasNext() ?  Util.toIntUnsigned(xIt.next()) : -1);
            }
            else if (thisHead < xHead) {
                // item present in this only.  We want for OR, XOR plus ANDNOT  (all except AND)
                if (opcode != OP_AND) 
                    answer.add((short) thisHead);
                thisHead = (it.hasNext() ?  Util.toIntUnsigned(it.next()) : -1);
            }
            else { // item is present in both x and this;   AND and OR should get it, but not XOR or ANDNOT
                if (opcode == OP_AND || opcode == OP_OR) 
                    answer.add((short) thisHead);
                thisHead = (it.hasNext() ?  Util.toIntUnsigned(it.next()) : -1);
                xHead =  (xIt.hasNext() ?  Util.toIntUnsigned(xIt.next()) : -1);
            }
        }

        // AND does not care if there are extra items in either 
        if (opcode != OP_AND) {

            // OR, ANDNOT, XOR all want extra items in this sequence
            while (thisHead != -1) {
                answer.add((short) thisHead);
                thisHead = (it.hasNext() ?  Util.toIntUnsigned(it.next()) : -1);
            }

            // OR and XOR want extra items in x sequence
            if (opcode == OP_OR || opcode == OP_XOR)
                while (xHead != -1) {
                    answer.add((short) xHead);
                    xHead =  (xIt.hasNext() ?  Util.toIntUnsigned(xIt.next()) : -1);
                } 
        }

        if (answer.getCardinality() <= ArrayContainer.DEFAULT_MAX_SIZE)
            return answer.toArrayContainer();
        else
            return answer;
    }*/


  
    //@Override
      public Container andNoSkip(RunContainer x) {
        /*
         * Main idea here: if you have two RunContainers, why 
         * not output the result as a RunContainer? Well,
         * result might not be storage efficient...
         * 
         * So that is the one catch.
         * 
         * TODO: this could be optimized if one has far fewer
         * runs than the other...
         * TODO: make sure buffer version is updated as well.
         */
        RunContainer answer = new RunContainer(0,new short[2 * (this.nbrruns + x.nbrruns)]);
        int rlepos = 0;
        int xrlepos = 0;
        int start = Util.toIntUnsigned(this.getValue(rlepos));
        int end = Util.toIntUnsigned(this.getValue(rlepos)) + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
        int xstart = Util.toIntUnsigned(x.getValue(xrlepos));
        int xend = Util.toIntUnsigned(x.getValue(xrlepos)) + Util.toIntUnsigned(x.getLength(xrlepos)) + 1;
        while ((rlepos < this.nbrruns ) && (xrlepos < x.nbrruns )) {
            if (end  <= xstart) {
                // exit the first run
                rlepos++;
                if(rlepos < this.nbrruns ) {
                    start = Util.toIntUnsigned(this.getValue(rlepos));
                    end = Util.toIntUnsigned(this.getValue(rlepos)) + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
                }
            } else if (xend <= start) {
                // exit the second run
                xrlepos++;
                if(xrlepos < x.nbrruns ) {
                    xstart = Util.toIntUnsigned(x.getValue(xrlepos));
                    xend = Util.toIntUnsigned(x.getValue(xrlepos)) + Util.toIntUnsigned(x.getLength(xrlepos)) + 1;
                }
            } else {// they overlap
                final int lateststart = start > xstart ? start : xstart;
                int earliestend;
                if(end == xend) {// improbable
                    earliestend = end;
                    rlepos++;
                    xrlepos++;
                    if(rlepos < this.nbrruns ) {
                        start = Util.toIntUnsigned(this.getValue(rlepos));
                        end = Util.toIntUnsigned(this.getValue(rlepos)) + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
                    }
                    if(xrlepos < x.nbrruns) {
                        xstart = Util.toIntUnsigned(x.getValue(xrlepos));
                        xend = Util.toIntUnsigned(x.getValue(xrlepos)) + Util.toIntUnsigned(x.getLength(xrlepos)) + 1;
                    }
                } else if(end < xend) {
                    earliestend = end;
                    rlepos++;
                    if(rlepos < this.nbrruns ) {
                        start = Util.toIntUnsigned(this.getValue(rlepos));
                        end = Util.toIntUnsigned(this.getValue(rlepos)) + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
                    }

                } else {// end > xend
                    earliestend = xend;
                    xrlepos++;
                    if(xrlepos < x.nbrruns) {
                        xstart = Util.toIntUnsigned(x.getValue(xrlepos));
                        xend = Util.toIntUnsigned(x.getValue(xrlepos)) + Util.toIntUnsigned(x.getLength(xrlepos)) + 1;
                    }                
                }
                answer.valueslength[2 * answer.nbrruns] = (short) lateststart;
                answer.valueslength[2 * answer.nbrruns + 1] = (short) (earliestend - lateststart - 1);
                answer.nbrruns++;
            }
        }
        return answer.toEfficientContainer();  // subsequent trim() may be required to avoid wasted space.
    }


    // bootstrapping (aka "galloping")  binary search.  Always skips at least one.
    // On our "real data" benchmarks, enabling galloping is a minor loss

    //.."ifdef ENABLE_GALLOPING_AND"   :)
    private int skipAhead(RunContainer skippingOn, int pos, int targetToExceed) {
        int left=pos;
        int span=1;
        int probePos=0;
        int end;
        // jump ahead to find a spot where end > targetToExceed (if it exists)
        do {
            probePos = left + span;
            if (probePos >= skippingOn.nbrruns - 1)  {
                // expect it might be quite common to find the container cannot be advanced as far as requested.  Optimize for it.
                probePos = skippingOn.nbrruns - 1;
                end = Util.toIntUnsigned(skippingOn.getValue(probePos)) + Util.toIntUnsigned(skippingOn.getLength(probePos)) + 1; 
                if (end <= targetToExceed) 
                    return skippingOn.nbrruns;
            }
            end = Util.toIntUnsigned(skippingOn.getValue(probePos)) + Util.toIntUnsigned(skippingOn.getLength(probePos)) + 1;
            span *= 2;
        }  while (end <= targetToExceed);
        int right = probePos;
        // left and right are both valid positions.  Invariant: left <= targetToExceed && right > targetToExceed
        // do a binary search to discover the spot where left and right are separated by 1, and invariant is maintained.
        while (right - left > 1) {
            int mid =  (right + left)/2;
            int midVal =  Util.toIntUnsigned(skippingOn.getValue(mid)) + Util.toIntUnsigned(skippingOn.getLength(mid)) + 1; 
            if (midVal > targetToExceed) 
                right = mid;
            else
                left = mid;
        }
        return right;
    }

    @Override
      public Container and(RunContainer x) {
        /*
         * Main idea here: if you have two RunContainers, why 
         * not output the result as a RunContainer? Well,
         * result might not be storage efficient...
         * 
         * So that is the one catch.
         * 
         * TODO: this could be optimized if one has far fewer
         * runs than the other...
         * TODO: make sure buffer version is updated as well.
         */
        RunContainer answer = new RunContainer(0,new short[2 * (this.nbrruns + x.nbrruns)]);
        int rlepos = 0;
        int xrlepos = 0;
        // remove on cleanup...stuff to see why galloping is not a win
        //        int countSkips=0;
        //        int totalSkipLen=0;
        int start = Util.toIntUnsigned(this.getValue(rlepos));
        int end = Util.toIntUnsigned(this.getValue(rlepos)) + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
        int xstart = Util.toIntUnsigned(x.getValue(xrlepos));
        int xend = Util.toIntUnsigned(x.getValue(xrlepos)) + Util.toIntUnsigned(x.getLength(xrlepos)) + 1;
        while ((rlepos < this.nbrruns ) && (xrlepos < x.nbrruns )) {
            if (end  <= xstart) {
                if (ENABLE_GALLOPING_AND) {
                    // int temptemp_old = rlepos;
                    rlepos = skipAhead(this, rlepos, xstart); // skip over runs until we have end > xstart  (or rlepos is advanced beyond end)
                    // ++countSkips;
                    //totalSkipLen += (rlepos - temptemp_old);
                }
                else
                    ++rlepos;

                if(rlepos < this.nbrruns ) {
                    start = Util.toIntUnsigned(this.getValue(rlepos));
                    end = Util.toIntUnsigned(this.getValue(rlepos)) + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
                }
            } else if (xend <= start) {
                // exit the second run
                if (ENABLE_GALLOPING_AND) {
                    // int temptemp_old = xrlepos;
                    xrlepos = skipAhead(x, xrlepos, start);
                    //++countSkips;
                    //totalSkipLen += (xrlepos - temptemp_old);
                }
                else
                    ++xrlepos;

                if(xrlepos < x.nbrruns ) {
                    xstart = Util.toIntUnsigned(x.getValue(xrlepos));
                    xend = Util.toIntUnsigned(x.getValue(xrlepos)) + Util.toIntUnsigned(x.getLength(xrlepos)) + 1;
                }
            } else {// they overlap
                final int lateststart = start > xstart ? start : xstart;
                int earliestend;
                if(end == xend) {// improbable
                    earliestend = end;
                    rlepos++;
                    xrlepos++;
                    if(rlepos < this.nbrruns ) {
                        start = Util.toIntUnsigned(this.getValue(rlepos));
                        end = Util.toIntUnsigned(this.getValue(rlepos)) + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
                    }
                    if(xrlepos < x.nbrruns) {
                        xstart = Util.toIntUnsigned(x.getValue(xrlepos));
                        xend = Util.toIntUnsigned(x.getValue(xrlepos)) + Util.toIntUnsigned(x.getLength(xrlepos)) + 1;
                    }
                } else if(end < xend) {
                    earliestend = end;
                    rlepos++;
                    if(rlepos < this.nbrruns ) {
                        start = Util.toIntUnsigned(this.getValue(rlepos));
                        end = Util.toIntUnsigned(this.getValue(rlepos)) + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
                    }

                } else {// end > xend
                    earliestend = xend;
                    xrlepos++;
                    if(xrlepos < x.nbrruns) {
                        xstart = Util.toIntUnsigned(x.getValue(xrlepos));
                        xend = Util.toIntUnsigned(x.getValue(xrlepos)) + Util.toIntUnsigned(x.getLength(xrlepos)) + 1;
                    }                
                }
                answer.valueslength[2 * answer.nbrruns] = (short) lateststart;
                answer.valueslength[2 * answer.nbrruns + 1] = (short) (earliestend - lateststart - 1);
                answer.nbrruns++;
            }
        }

        // remove on cleanup
        //if (countSkips > 0)
        //   System.out.println("container avg skip amount is "+
        //                        ( totalSkipLen / ( (double) countSkips)));

        return answer.toEfficientContainer();  // subsequent trim() may be required to avoid wasted space.
    }







    @Override
    public Container andNot(RunContainer x) {
        /*
         * Main idea here: if you have two RunContainers, why 
         * not output the result as a RunContainer? Well,
         * result might not be storage efficient...
         * 
         * So that is the one catch.
         * 
         * TODO: this could be optimized if one has far fewer
         * runs than the other...
         * 
         * TODO: make sure buffer version is updated as well.
         */
        RunContainer answer = new RunContainer(0,new short[2 * (this.nbrruns + x.nbrruns)]);
        int rlepos = 0;
        int xrlepos = 0;
        int start = Util.toIntUnsigned(this.getValue(rlepos));
        int end = Util.toIntUnsigned(this.getValue(rlepos)) + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
        int xstart = Util.toIntUnsigned(x.getValue(xrlepos));
        int xend = Util.toIntUnsigned(x.getValue(xrlepos)) + Util.toIntUnsigned(x.getLength(xrlepos)) + 1;
        while ((rlepos < this.nbrruns ) && (xrlepos < x.nbrruns )) {
            if (end  <= xstart) {
                // output the first run
                answer.valueslength[2 * answer.nbrruns] = (short) start;
                answer.valueslength[2 * answer.nbrruns + 1] = (short)(end - start - 1);
                answer.nbrruns++;
                rlepos++;
                if(rlepos < this.nbrruns ) {
                    start = Util.toIntUnsigned(this.getValue(rlepos));
                    end = start + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
                }
            } else if (xend <= start) {
                // exit the second run
                xrlepos++;
                if(xrlepos < x.nbrruns ) {
                    xstart = Util.toIntUnsigned(x.getValue(xrlepos));
                    xend = Util.toIntUnsigned(x.getValue(xrlepos)) + Util.toIntUnsigned(x.getLength(xrlepos)) + 1;
                }
            } else {
                if ( start < xstart ) {
                    answer.valueslength[2 * answer.nbrruns] = (short) start;
                    answer.valueslength[2 * answer.nbrruns + 1] = (short) (xstart - start - 1);
                    answer.nbrruns++;
                }
                if(xend < end) {
                    start = xend;
                } else {
                    rlepos++;
                    if(rlepos < this.nbrruns ) {
                        start = Util.toIntUnsigned(this.getValue(rlepos));
                        end = start + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
                    }
                }
            }
        }
        if(rlepos < this.nbrruns) {
            answer.valueslength[2 * answer.nbrruns] = (short) start;
            answer.valueslength[2 * answer.nbrruns + 1] = (short)(end - start - 1);
            answer.nbrruns++;
            rlepos++;
            if(rlepos < this.nbrruns ) {
                System.arraycopy(this.valueslength, 2 * rlepos, answer.valueslength, 2 * answer.nbrruns, 2*(this.nbrruns-rlepos ));
                answer.nbrruns  = answer.nbrruns + this.nbrruns - rlepos;
            } 
        }
        return answer.toEfficientContainer();
        /*double myDensity = getDensity();
        double xDensity = x.getDensity();
        double resultDensityEstimate = myDensity*(1-xDensity);
        return (resultDensityEstimate < ARRAY_MAX_DENSITY ? operationArrayGuess(x, OP_ANDNOT) : operationBitmapGuess(x, OP_ANDNOT));*/
    }

    // assume that the (maybe) inplace operations
    // will never actually *be* in place if they are 
    // to return ArrayContainer or BitmapContainer

    @Override
    public Container iand(RunContainer x) {
        return and(x);
    }

    @Override
    public Container iandNot(RunContainer x) {
        return andNot(x);
    }

    @Override
    public Container ior(RunContainer x) {
        return or(x);
    }

    @Override
    public Container ixor(RunContainer x) {
        return xor(x);
    }

    @Override
    public Container or(RunContainer x) {
        /*
         * Main idea here: if you have two RunContainers, why 
         * not output the result as a RunContainer?
         * 
         * TODO: make sure buffer version is updated as well.
         */
        RunContainer answer = new RunContainer(0,new short[2 * (this.nbrruns + x.nbrruns)]);
        int rlepos = 0;
        int xrlepos = 0;
        int start = Util.toIntUnsigned(this.getValue(rlepos));
        int end = start + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
        int xstart = Util.toIntUnsigned(x.getValue(xrlepos));
        int xend = xstart + Util.toIntUnsigned(x.getLength(xrlepos)) + 1;

        while ((rlepos < this.nbrruns ) && (xrlepos < x.nbrruns )) {
            if (end  < xstart) {
                // output the first run
                answer.valueslength[2 * answer.nbrruns] = this.valueslength[2 * rlepos];
                answer.valueslength[2 * answer.nbrruns + 1] = this.valueslength[2 * rlepos + 1];
                answer.nbrruns++;
                rlepos++;
                if(rlepos < this.nbrruns ) {
                    start = Util.toIntUnsigned(this.getValue(rlepos));
                    end = start + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
                }
            } else if (xend < start) {
                // output the second run
                answer.valueslength[2 * answer.nbrruns] = x.valueslength[2 * xrlepos];
                answer.valueslength[2 * answer.nbrruns + 1] = x.valueslength[2 * xrlepos + 1];
                answer.nbrruns++;
                xrlepos++;
                if(xrlepos < x.nbrruns ) {
                    xstart = Util.toIntUnsigned(x.getValue(xrlepos));
                    xend = xstart + Util.toIntUnsigned(x.getLength(xrlepos)) + 1;
                }
            } else {// they overlap or are right next to each other
                final int earlieststart = start < xstart ? start : xstart;
                int maxend = end < xend ? xend : end;
                while (true) {
                    if (end == xend) { // improbable
                        break;
                    } else if (end < xend) {
                        // we can advance the first
                        rlepos++;
                        if (rlepos < this.nbrruns) {
                            start = Util.toIntUnsigned(this.getValue(rlepos));
                            end = start
                                    + Util.toIntUnsigned(this.getLength(rlepos))
                                    + 1;
                            if (start < maxend) {
                                if (end > maxend) {
                                    maxend = end;
                                }
                            } else {
                                break;
                            }
                        } else {
                            break;
                        }
                    } else {// end > xend
                        // we can advance the second
                        xrlepos++;
                        if (xrlepos < x.nbrruns) {
                            xstart = Util.toIntUnsigned(x.getValue(xrlepos));
                            xend = xstart
                                    + Util.toIntUnsigned(x.getLength(xrlepos))
                                    + 1;
                            if (xstart < maxend) {
                                if (xend > maxend) {
                                    maxend = xend;
                                }
                            } else {
                                break;
                            }
                        } else {
                            break;
                        }
                    }
                }
                if((rlepos < this.nbrruns) && (start < maxend)) {
                    rlepos++;
                    if (rlepos < this.nbrruns) {
                        start = Util.toIntUnsigned(this.getValue(rlepos));
                        end = start
                                + Util.toIntUnsigned(this.getLength(rlepos))
                                + 1;
                    }
                }
                if((xrlepos < x.nbrruns) && (xstart < maxend)) {
                    xrlepos++;
                    if (xrlepos < x.nbrruns) {
                        xstart = Util.toIntUnsigned(x.getValue(xrlepos));
                        xend = xstart
                                + Util.toIntUnsigned(x.getLength(xrlepos))
                                + 1;
                    }
                }
                answer.valueslength[2 * answer.nbrruns] = (short) earlieststart;
                answer.valueslength[2 * answer.nbrruns + 1] = (short) (maxend - earlieststart - 1);
                answer.nbrruns++;
            }
        }
        if(rlepos < this.nbrruns) {
            System.arraycopy(this.valueslength, 2 * rlepos, answer.valueslength, 2 * answer.nbrruns, 2*(this.nbrruns-rlepos ));
            answer.nbrruns = answer.nbrruns + this.nbrruns - rlepos;
        }
        if(xrlepos < x.nbrruns) {
            System.arraycopy(x.valueslength, 2 * xrlepos, answer.valueslength, 2 * answer.nbrruns, 2*(x.nbrruns-xrlepos ));
            answer.nbrruns = answer.nbrruns + x.nbrruns - xrlepos;
        }
        return answer;

        /*
        double myDensity = getDensity();
        double xDensity = x.getDensity();
        double resultDensityEstimate = 1- (1-myDensity)*(1-xDensity);
        return (resultDensityEstimate < ARRAY_MAX_DENSITY ? operationArrayGuess(x, OP_OR) : operationBitmapGuess(x, OP_OR));
         */
    }

    @Override
    public Container xor(RunContainer x) {
        /*
         * Main idea here: if you have two RunContainers, why 
         * not output the result as a RunContainer?
         * 
         * Downside: the output could be storage inefficient
         * 
         * TODO: make sure buffer version is updated as well.
         */
        RunContainer answer = new RunContainer(0,new short[2 * (this.nbrruns + x.nbrruns)]);
        int rlepos = 0;
        int xrlepos = 0;
        int start = Util.toIntUnsigned(this.getValue(rlepos));
        int end = start + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
        int xstart = Util.toIntUnsigned(x.getValue(xrlepos));
        int xend = xstart + Util.toIntUnsigned(x.getLength(xrlepos)) + 1;

        while ((rlepos < this.nbrruns ) && (xrlepos < x.nbrruns )) {
            if (end  <= xstart) {
                // output the first run
                answer.valueslength[2 * answer.nbrruns] = this.valueslength[2 * rlepos];
                answer.valueslength[2 * answer.nbrruns + 1] = this.valueslength[2 * rlepos + 1];
                answer.nbrruns++;
                rlepos++;
                if(rlepos < this.nbrruns ) {
                    start = Util.toIntUnsigned(this.getValue(rlepos));
                    end = start + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
                }
            } else if (xend <= start) {
                // output the second run
                answer.valueslength[2 * answer.nbrruns] = x.valueslength[2 * xrlepos];
                answer.valueslength[2 * answer.nbrruns + 1] = x.valueslength[2 * xrlepos + 1];
                answer.nbrruns++;
                xrlepos++;
                if(xrlepos < x.nbrruns ) {
                    xstart = Util.toIntUnsigned(x.getValue(xrlepos));
                    xend = xstart + Util.toIntUnsigned(x.getLength(xrlepos)) + 1;
                }
            } else {// they overlap
                int startpoint = start < xstart ? start : xstart;
                do {
                    if( startpoint < xstart ) {
                        answer.valueslength[2 * answer.nbrruns] = (short) startpoint;
                        answer.valueslength[2 * answer.nbrruns + 1] = (short) (xstart - startpoint - 1);
                        answer.nbrruns++;

                    } else if(startpoint < start) {
                        answer.valueslength[2 * answer.nbrruns] = (short) startpoint;
                        answer.valueslength[2 * answer.nbrruns + 1] = (short) (start - startpoint - 1);
                        answer.nbrruns++;                
                    }
                    if(end == xend) {// improbable
                        startpoint = end;
                        rlepos++;
                        xrlepos++;
                        if(rlepos < this.nbrruns ) {
                            start = Util.toIntUnsigned(this.getValue(rlepos));
                            end = start + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
                        }
                        if(xrlepos < x.nbrruns) {
                            xstart = Util.toIntUnsigned(x.getValue(xrlepos));
                            xend = xstart + Util.toIntUnsigned(x.getLength(xrlepos)) + 1;
                        }
                        break;
                    } else if(end < xend) {
                        startpoint = end;
                        rlepos++;
                        if(rlepos < this.nbrruns ) {
                            start = Util.toIntUnsigned(this.getValue(rlepos));
                            end = start + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
                            if(start >= xend) break;
                        } else break;

                    } else {// end > xend
                        startpoint =  xend;
                        xrlepos++;
                        if(xrlepos < x.nbrruns) {
                            xstart = Util.toIntUnsigned(x.getValue(xrlepos));
                            xend = xstart + Util.toIntUnsigned(x.getLength(xrlepos)) + 1;
                            if(xstart >= end) break;
                        } else break;               
                    }


                } while (true);
                if((start < startpoint) && (startpoint < end)) {
                    answer.valueslength[2 * answer.nbrruns] = (short) startpoint;
                    answer.valueslength[2 * answer.nbrruns + 1] = (short) (end - startpoint - 1);
                    answer.nbrruns++;                
                    rlepos++;
                    if(rlepos < this.nbrruns ) {
                        start = Util.toIntUnsigned(this.getValue(rlepos));
                        end = start + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
                    }
                } else if((xstart < startpoint) && (startpoint < xend) ){
                    answer.valueslength[2 * answer.nbrruns] = (short) startpoint;
                    answer.valueslength[2 * answer.nbrruns + 1] = (short) (xend - startpoint - 1);
                    answer.nbrruns++;                
                    xrlepos++;
                    if(xrlepos < x.nbrruns) {
                        xstart = Util.toIntUnsigned(x.getValue(xrlepos));
                        xend = xstart + Util.toIntUnsigned(x.getLength(xrlepos)) + 1;
                    } 
                }
            }
        }
        if(rlepos < this.nbrruns) {
            System.arraycopy(this.valueslength, 2 * rlepos, answer.valueslength, 2 * answer.nbrruns, 2*(this.nbrruns-rlepos ));
            answer.nbrruns = answer.nbrruns + this.nbrruns - rlepos;
        }
        if(xrlepos < x.nbrruns) {
            System.arraycopy(x.valueslength, 2 * xrlepos, answer.valueslength, 2 * answer.nbrruns, 2*(x.nbrruns-xrlepos ));
            answer.nbrruns = answer.nbrruns + x.nbrruns - xrlepos;
        }
        /*
        while(rlepos < this.nbrruns) {
            answer.valueslength[2 * answer.nbrruns] = this.valueslength[2 * rlepos];
            answer.valueslength[2 * answer.nbrruns + 1] = this.valueslength[2 * rlepos + 1];
            answer.nbrruns++;
            rlepos++;        
        } 
        while(xrlepos < x.nbrruns) {
            answer.valueslength[2 * answer.nbrruns] = x.valueslength[2 * xrlepos];
            answer.valueslength[2 * answer.nbrruns + 1] = x.valueslength[2 * xrlepos + 1];
            answer.nbrruns++;
            xrlepos++;
        }*/
        return answer.toEfficientContainer();
        /*
        double myDensity = getDensity();
        double xDensity = x.getDensity();
        double resultDensityEstimate = 1- (1-myDensity)*(1-xDensity)  - myDensity*xDensity;  // I guess
        return (resultDensityEstimate < ARRAY_MAX_DENSITY ? operationArrayGuess(x, OP_XOR) : operationBitmapGuess(x, OP_XOR));
         */
    }

}


final class RunContainerShortIterator implements ShortIterator {
    int pos;
    int le = 0;
    int maxlength;
    int base;

    RunContainer parent;

    RunContainerShortIterator() {}

    RunContainerShortIterator(RunContainer p) {
        wrap(p);
    }

    void wrap(RunContainer p) {
        parent = p;
        pos = 0;
        le = 0;
        if(pos < parent.nbrruns) {
            maxlength = Util.toIntUnsigned(parent.getLength(pos));
            base = Util.toIntUnsigned(parent.getValue(pos));
        }
    }

    @Override
    public boolean hasNext() {
        return pos < parent.nbrruns;
    }

    @Override
    public ShortIterator clone() {
        try {
            return (ShortIterator) super.clone();
        } catch (CloneNotSupportedException e) {
            return null;// will not happen
        }
    }

    @Override
    public short next() {
        short ans = (short) (base + le);
        le++;
        if(le > maxlength) {
            pos++;
            le = 0;
            if(pos < parent.nbrruns) {
                maxlength = Util.toIntUnsigned(parent.getLength(pos));
                base = Util.toIntUnsigned(parent.getValue(pos));
            }
        }
        return ans;
    }

    @Override
    public int nextAsInt() {
        int ans = base + le;
        le++;
        if(le > maxlength) {
            pos++;
            le = 0;
            if(pos < parent.nbrruns) {
                maxlength = Util.toIntUnsigned(parent.getLength(pos));
                base = Util.toIntUnsigned(parent.getValue(pos));
            }
        }
        return ans;
    }

    @Override
    public void remove() {
        throw new RuntimeException("Not implemented");// TODO
    }

};

final class ReverseRunContainerShortIterator implements ShortIterator {
    int pos;
    int le;
    RunContainer parent;
    int maxlength;
    int base;


    ReverseRunContainerShortIterator(){}

    ReverseRunContainerShortIterator(RunContainer p) {
        wrap(p);
    }

    void wrap(RunContainer p) {
        parent = p;
        pos = parent.nbrruns - 1;
        le = 0;
        if(pos >= 0) {
            maxlength = Util.toIntUnsigned(parent.getLength(pos));
            base = Util.toIntUnsigned(parent.getValue(pos));
        }
    }

    @Override
    public boolean hasNext() {
        return pos >= 0;
    }

    @Override
    public ShortIterator clone() {
        try {
            return (ShortIterator) super.clone();
        } catch (CloneNotSupportedException e) {
            return null;// will not happen
        }
    }

    @Override
    public short next() {
        short ans = (short) (base + maxlength - le);
        le++;
        if(le > maxlength) {
            pos--;
            le = 0;
            if(pos >= 0) {
                maxlength = Util.toIntUnsigned(parent.getLength(pos));
                base = Util.toIntUnsigned(parent.getValue(pos));
            }
        }
        return ans;
    }
    
    @Override
    public int nextAsInt() {
        int ans = base + maxlength - le;
        le++;
        if(le > maxlength) {
            pos--;
            le = 0;
            if(pos >= 0) {
                maxlength = Util.toIntUnsigned(parent.getLength(pos));
                base = Util.toIntUnsigned(parent.getValue(pos));
            }
        }
        return ans;
    }

    @Override
    public void remove() {
        throw new RuntimeException("Not implemented");// TODO
    }

}

