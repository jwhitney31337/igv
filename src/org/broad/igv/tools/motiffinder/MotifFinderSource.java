/*
 * Copyright (c) 2007-2012 The Broad Institute, Inc.
 * SOFTWARE COPYRIGHT NOTICE
 * This software and its documentation are the copyright of the Broad Institute, Inc. All rights are reserved.
 *
 * This software is supplied without any warranty or guaranteed support whatsoever. The Broad Institute is not responsible for its use, misuse, or functionality.
 *
 * This software is licensed under the terms of the GNU Lesser General Public License (LGPL),
 * Version 2.1 which is available at http://www.opensource.org/licenses/lgpl-2.1.php.
 */

package org.broad.igv.tools.motiffinder;

import com.google.common.collect.Iterators;
import net.sf.samtools.util.SequenceUtil;
import org.broad.igv.dev.api.IGVPlugin;
import org.broad.igv.feature.*;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.session.SessionXmlAdapters;
import org.broad.igv.session.SubtlyImportant;
import org.broad.igv.track.FeatureSource;
import org.broad.igv.track.FeatureTrack;
import org.broad.igv.track.Track;
import org.broad.igv.ui.IGV;
import org.broad.igv.ui.PanelName;
import org.broad.tribble.Feature;

import javax.swing.*;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Class for searching for pattern in a shortSeq.
 *
 * We recognize single letter codes, including ambiguity codes,
 * only.
 * See http://en.wikipedia.org/wiki/Nucleic_acid_notation
 * or http://www.chem.qmul.ac.uk/iubmb/misc/naseq.html
 * User: jacob
 * Date: 2013-Jan-22
 */
@XmlAccessorType(XmlAccessType.NONE)
public class MotifFinderSource implements FeatureSource<Feature> {

    @XmlAttribute private String pattern;

    @XmlJavaTypeAdapter(SessionXmlAdapters.Genome.class)
    @XmlAttribute private Genome genome;

    @XmlAttribute private int featureWindowSize = (int) 100e3;

    @SubtlyImportant
    private MotifFinderSource(){}

    /**
     *
     * @param pattern The regex pattern to search
     * @param genome Genome from which to get sequence data
     */
    public MotifFinderSource(String pattern, Genome genome){
        this.pattern = pattern;
        this.genome = genome;
    }

    /**
     * See {@link #search(String, String, int, byte[])} for explanation of parameters
     * @param chr
     * @param strand POSITIVE or NEGATIVE
     * @param pattern
     * @param posStart
     * @param sequence
     * @return
     */
    static Iterator<Feature> searchSingleStrand(String chr, Strand strand, String pattern, int posStart, byte[] sequence){
        Matcher matcher = getMatcher(pattern, strand, sequence);
        return new MatchFeatureIterator(chr, strand, posStart, sequence.length, matcher);
    }

    /**
     * See {@link #search(String, String, int, byte[])} for explanation of parameters
     * @param pattern
     * @param strand
     * @param sequence
     * @return
     */
    static Matcher getMatcher(String pattern, Strand strand, byte[] sequence){
        byte[] seq = sequence;
        if(strand == Strand.NEGATIVE){
            seq = seq.clone();
            SequenceUtil.reverseComplement(seq);
        }
        Pattern regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        String stringSeq = new String(seq);
        return regex.matcher(stringSeq);
    }

    /**
     * Search the provided sequence for the provided pattern
     * {@code chr} and {@code seqStart} are used in constructing the resulting
     * {@code Feature}s. Search is performed over positive and negative strand
     * @param chr
     * @param pattern
     * @param posStart The 0-based offset from the beginning of the genome that the {@code sequence} is based
     *                 Always relative to positive strand
     * @param sequence The positive-strand nucleotide sequence
     * @return
     */
    public static Iterator<Feature> search(String chr, String pattern, int posStart, byte[] sequence){

        //Get positive strand features
        Iterator<Feature> posIter = searchSingleStrand(chr, Strand.POSITIVE, pattern, posStart, sequence);

        //Get negative strand features.
        List<Feature> negStrandFeatures = new ArrayList<Feature>();
        Iterator<Feature> negIter = searchSingleStrand(chr, Strand.NEGATIVE, pattern, posStart, sequence);
        Iterators.addAll(negStrandFeatures, negIter);
        Collections.reverse(negStrandFeatures);

        return Iterators.mergeSorted(Arrays.asList(posIter, negStrandFeatures.iterator()), FeatureUtils.FEATURE_START_COMPARATOR);
    }

    @Override
    public Iterator<Feature> getFeatures(String chr, int start, int end) throws IOException {
        byte[] seq = genome.getSequence(chr, start, end);
        if(seq == null) Collections.emptyList().iterator();
        return search(chr, this.pattern, start, seq);
    }

    @Override
    public List<LocusScore> getCoverageScores(String chr, int start, int end, int zoom) {
        //TODO Precalculate and/or store?
        return null;
    }

    @Override
    public int getFeatureWindowSize() {
        return this.featureWindowSize;
    }

    @Override
    public void setFeatureWindowSize(int size) {
        this.featureWindowSize = size;
    }

    public static class MotifFinderPlugin implements IGVPlugin {

        /**
         * Add menu entry for activating SequenceMatchDialog
         */
        @Override
        public void init() {
            JMenuItem menuItem = new JMenuItem("Find Motif...");
            menuItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    MotifFinderDialog dialog = new MotifFinderDialog(IGV.getMainFrame());
                    dialog.setVisible(true);

                    String trackName = dialog.getTrackName();
                    String pattern = dialog.getInputPattern();
                    if (pattern != null) {
                        MotifFinderSource source = new MotifFinderSource(pattern, GenomeManager.getInstance().getCurrentGenome());
                        CachingFeatureSource cachingFeatureSource = new CachingFeatureSource(source);
                        FeatureTrack track = new FeatureTrack(trackName, trackName, cachingFeatureSource);
                        IGV.getInstance().addTracks(Arrays.<Track>asList(track), PanelName.FEATURE_PANEL);
                    }
                }
            });

            IGV.getInstance().addOtherToolMenu(menuItem);
        }
    }

    /**
     * Iterator which turns regex Matcher results into Features.
     * The ordering of features will be either forwards or backwards, depending
     * on the strand.
     */
    private static class MatchFeatureIterator implements Iterator<Feature>{

        private String chr;
        private int posOffset;
        private Strand strand;

        /**
         * Number of characters from the start of the string at which the
         * last match was found.
         *
         * We want to find overlapping matches. By default, matcher.find()
         * starts from the end of the previous match, which would preclude overlapping matches.
         * By storing the last start position we reset each time
         */
        private int lastMatchStart = -1;
        
        private Matcher matcher;

        private IGVFeature nextFeat;

        /**
         * 
         * @param chr The chromosome we are searching
         * @param strand The strand we are searching
         * @param posOffset The position within the chromosome that we started searching.
         *                  Always in positive strand coordinates
         * @param sequenceLength Length of the string which we are matching
         * @param matcher Matcher over sequence.
         */
        private MatchFeatureIterator(String chr, Strand strand, int posOffset, int sequenceLength, Matcher matcher){
            this.chr = chr;
            this.strand = strand;
            this.posOffset = posOffset;
            this.matcher = matcher;
            if(this.strand == Strand.NEGATIVE){
                this.posOffset += sequenceLength;
            }
            findNext();
        }

        private void findNext(){
            if(matcher.find(lastMatchStart + 1)){
                lastMatchStart = matcher.start();
                //The start/end coordinates are always in positive-strand coordinates
                int start, end;
                if(strand == Strand.POSITIVE){
                    start = posOffset + lastMatchStart;
                    end = posOffset + matcher.end();
                }else{
                    start = posOffset - matcher.end();
                    end = posOffset - lastMatchStart;
                }
                nextFeat = new BasicFeature(chr, start, end, this.strand);
            }else{
                nextFeat = null;
            }
        }

        @Override
        public boolean hasNext() {
            return nextFeat != null;
        }

        @Override
        public IGVFeature next() {
            IGVFeature nF = nextFeat;
            findNext();
            return nF;
        }

        @Override
        public void remove() {
            throw new RuntimeException("Cannot remove from this iterator");
        }
    }
}