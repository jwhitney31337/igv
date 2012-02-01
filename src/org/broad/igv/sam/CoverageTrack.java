/*
 * Copyright (c) 2007-2011 by The Broad Institute of MIT and Harvard.  All Rights Reserved.
 *
 * This software is licensed under the terms of the GNU Lesser General Public License (LGPL),
 * Version 2.1 which is available at http://www.opensource.org/licenses/lgpl-2.1.php.
 *
 * THE SOFTWARE IS PROVIDED "AS IS." THE BROAD AND MIT MAKE NO REPRESENTATIONS OR
 * WARRANTES OF ANY KIND CONCERNING THE SOFTWARE, EXPRESS OR IMPLIED, INCLUDING,
 * WITHOUT LIMITATION, WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE, NONINFRINGEMENT, OR THE ABSENCE OF LATENT OR OTHER DEFECTS, WHETHER
 * OR NOT DISCOVERABLE.  IN NO EVENT SHALL THE BROAD OR MIT, OR THEIR RESPECTIVE
 * TRUSTEES, DIRECTORS, OFFICERS, EMPLOYEES, AND AFFILIATES BE LIABLE FOR ANY DAMAGES
 * OF ANY KIND, INCLUDING, WITHOUT LIMITATION, INCIDENTAL OR CONSEQUENTIAL DAMAGES,
 * ECONOMIC DAMAGES OR INJURY TO PROPERTY AND LOST PROFITS, REGARDLESS OF WHETHER
 * THE BROAD OR MIT SHALL BE ADVISED, SHALL HAVE OTHER REASON TO KNOW, OR IN FACT
 * SHALL KNOW OF THE POSSIBILITY OF THE FOREGOING.
 */

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.broad.igv.sam;

import org.apache.log4j.Logger;
import org.broad.igv.PreferenceManager;
import org.broad.igv.data.CoverageDataSource;
import org.broad.igv.data.DataSource;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.goby.GobyCountArchiveDataSource;
import org.broad.igv.ui.color.ColorUtilities;
import org.broad.igv.ui.panel.IGVPopupMenu;
import org.broad.igv.ui.panel.ReferenceFrame;
import org.broad.igv.ui.util.FileDialogUtils;
import org.broad.igv.util.ResourceLocator;
import org.broad.igv.feature.FeatureUtils;
import org.broad.igv.feature.LocusScore;
import org.broad.igv.renderer.BarChartRenderer;
import org.broad.igv.renderer.DataRange;
import org.broad.igv.renderer.DataRenderer;
import org.broad.igv.renderer.Renderer;
import org.broad.igv.tdf.TDFDataSource;
import org.broad.igv.tdf.TDFReader;
import org.broad.igv.track.*;
import org.broad.igv.ui.DataRangeDialog;
import org.broad.igv.ui.FontManager;
import org.broad.igv.ui.IGV;
import org.broad.igv.ui.util.MessageUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.*;
import java.util.List;

/**
 * @author jrobinso
 */
public class CoverageTrack extends AbstractTrack {

    private static Logger log = Logger.getLogger(CoverageTrack.class);

    char[] nucleotides = {'a', 'c', 'g', 't', 'n'};
    public static Color lightBlue = new Color(0, 0, 150);
    private static Color coverageGrey = new Color(175, 175, 175);
    public static final Color negStrandColor = new Color(140, 140, 160);
    public static final Color posStrandColor = new Color(160, 140, 140);

    private static final boolean DEFAULT_AUTOSCALE = true;
    private static final boolean DEFAULT_SHOW_REFERENCE = false;

    // User settable state -- these attributes should be stored in the session file
    boolean showReference;
    boolean autoScale = DEFAULT_AUTOSCALE;
    private float snpThreshold;

    AlignmentDataManager dataManager;
    CoverageDataSource dataSource;
    DataRenderer dataSourceRenderer; // = new BarChartRenderer();
    IntervalRenderer intervalRenderer;
    PreferenceManager prefs;
    JMenuItem dataRangeItem;
    JMenuItem autoscaleItem;
    Genome genome;


    public CoverageTrack(ResourceLocator locator, String name, Genome genome) {
        super(locator, locator.getPath() + "_coverage", name);
        super.setDataRange(new DataRange(0, 0, 60));
        this.genome = genome;
        intervalRenderer = new IntervalRenderer();

        setColor(coverageGrey);

        prefs = PreferenceManager.getInstance();
        snpThreshold = prefs.getAsFloat(PreferenceManager.SAM_ALLELE_THRESHOLD);
        autoScale = DEFAULT_AUTOSCALE;
        showReference = DEFAULT_SHOW_REFERENCE;
        //TODO  logScale = prefs.


    }

    public void setDataManager(AlignmentDataManager dataManager) {
        this.dataManager = dataManager;
    }

    public void setDataSource(DataSource dataSource) {
        this.dataSource = (CoverageDataSource) dataSource;
        dataSourceRenderer = new BarChartRenderer();
        setDataRange(new DataRange(0, 0, 1.5f * (float) dataSource.getDataMax()));

    }


    @Override
    public void setDataRange(DataRange axisDefinition) {
        // Explicitly setting a data range turns off auto-scale
        autoScale = false;
        super.setDataRange(axisDefinition);
    }

    public void rescale() {
        if (autoScale & dataManager != null) {
            final Collection<AlignmentInterval> loadedIntervals = dataManager.getLoadedIntervals();
            if (loadedIntervals != null) {
                for (AlignmentInterval interval : loadedIntervals) {
                    rescaleInterval(interval);
                }
            }
        }
    }

    public void rescale(ReferenceFrame frame) {
        if (autoScale & dataManager != null) {
            rescaleInterval(dataManager.getLoadedInterval(frame));
        }
    }

    private void rescaleInterval(AlignmentInterval interval) {
        if (interval != null) {
            int max = Math.max(10, interval.getMaxCount());
            DataRange.Type type = getDataRange().getType();
            super.setDataRange(new DataRange(0, 0, max));
            getDataRange().setType(type);
        }
    }


    public void render(RenderContext context, Rectangle rect) {

        float maxRange = PreferenceManager.getInstance().getAsFloat(PreferenceManager.SAM_MAX_VISIBLE_RANGE);
        float minVisibleScale = (maxRange * 1000) / 700;

        if (context.getScale() < minVisibleScale) {
            //
            AlignmentInterval interval = null;
            if (dataManager != null) {
                interval = dataManager.getLoadedInterval(context.getReferenceFrame());
            }
            if (interval != null && interval.contains(context.getChr(), (int) context.getOrigin(), (int) context.getEndLocation())) {
                List<AlignmentCounts> counts = interval.getCounts();
                intervalRenderer.paint(context, rect, counts);
            }
        } else if (dataSource != null) {
            // Use precomputed data source, if any
            String chr = context.getChr();
            int start = (int) context.getOrigin();
            int end = (int) context.getEndLocation();
            int zoom = context.getZoom();
            List<LocusScore> scores = dataSource.getSummaryScoresForRange(chr, start, end, zoom);
            if (scores != null) {
                dataSourceRenderer.render(scores, context, rect, this);
            }

        }
        drawBorder(context, rect);
    }

    private void drawBorder(RenderContext context, Rectangle rect) {
        // Draw border
        context.getGraphic2DForColor(Color.gray).drawLine(
                rect.x, rect.y + rect.height,
                rect.x + rect.width, rect.y + rect.height);

        // Draw scale
        DataRange range = getDataRange();
        if (range != null) {
            Graphics2D g = context.getGraphic2DForColor(Color.black);
            Font font = g.getFont();
            Font smallFont = FontManager.getFont(8);
            try {
                g.setFont(smallFont);
                String scale = "[" + (int) range.getMinimum() + " - " +
                        (int) range.getMaximum() + "]";
                g.drawString(scale, rect.x + 5, rect.y + 10);

            } finally {
                g.setFont(font);
            }
        }
    }

    public void setWindowFunction(WindowFunction type) {
    }

    public WindowFunction getWindowFunction() {
        return null;
    }

    public void setRendererClass(Class rc) {
    }

    public Renderer getRenderer() {
        return null;
    }

    public boolean isLogNormalized() {
        return false;
    }

    public String getValueStringAt(String chr, double position, int y, ReferenceFrame frame) {

        float maxRange = PreferenceManager.getInstance().getAsFloat(PreferenceManager.SAM_MAX_VISIBLE_RANGE);
        float minVisibleScale = (maxRange * 1000) / 700;
        if (frame.getScale() < minVisibleScale) {
            if (frame.getScale() < minVisibleScale) {
                AlignmentInterval interval = dataManager.getLoadedInterval(frame);
                if (interval != null && interval.contains(chr, (int) position, (int) position)) {
                    final int pos = (int) position; // - 1;
                    AlignmentCounts counts = interval.getAlignmentCounts(pos);
                    if (counts != null) {
                        return counts.getValueStringAt(pos);
                    }
                }
            }
        } else {
            return getPrecomputedValueString(chr, position, frame);
        }
        return null;
    }

    private String getPrecomputedValueString(String chr, double position, ReferenceFrame frame) {

        if (dataSource == null) {
            return "";
        }
        int zoom = Math.max(0, frame.getZoom());
        List<LocusScore> scores = dataSource.getSummaryScoresForRange(chr, (int) position - 10, (int) position + 10, zoom);

        // give a 2 pixel window, otherwise very narrow features will be missed.
        double bpPerPixel = frame.getScale();
        double minWidth = 2 * bpPerPixel;    /* * */

        if (scores == null) {
            return "";
        } else {
            LocusScore score = (LocusScore) FeatureUtils.getFeatureAt(position, 0, scores);
            return score == null ? "" : "Mean count: " + score.getScore();
        }
    }

    public float getRegionScore(String chr, int start, int end, int zoom, RegionScoreType type, ReferenceFrame frame) {
        return 0;
    }


    class IntervalRenderer {

        private void paint(RenderContext context, Rectangle rect, List<AlignmentCounts> countList) {

            Graphics2D graphics = context.getGraphic2DForColor(coverageGrey);
            Graphics2D posGraphics = context.getGraphic2DForColor(posStrandColor);
            Graphics2D negGraphics = context.getGraphic2DForColor(negStrandColor);// Use precomputed data source, if anyR

            DataRange range = getDataRange();
            double max = range.isLog() ? Math.log10(range.getMaximum()) : range.getMaximum();

            int lastpX = -1;
            final double rectX = rect.getX();
            final double rectMaxX = rect.getMaxX();
            final double rectY = rect.getY();
            final double rectMaxY = rect.getMaxY();
            final double rectHeight = rect.getHeight();
            final double origin = context.getOrigin();
            final double colorScaleMax = getColorScale().getMaximum();
            final double scale = context.getScale();

            for (AlignmentCounts alignmentCounts : countList) {

                final int intervalEnd = alignmentCounts.getEnd();
                final int intervalStart = alignmentCounts.getStart();
                byte[] refBases = null;

                // Dont try to compute mismatches for intervals > 2 MB
                final int twoMB = 2000000;
                if ((intervalEnd - intervalStart) < twoMB) {
                    refBases = genome.getSequence(context.getChr(), intervalStart, intervalEnd);
                }

                int pos;
                AlignmentCounts.PositionIterator posIter = alignmentCounts.getPositionIterator();
                while ((pos = posIter.nextPosition()) >= 0) {

                    int pX = (int) (rectX + (pos - origin) / scale);
                    int dX = Math.max(1,
                            (int) (rectX + (pos + 1 - origin) / scale) - pX);
                    if (dX > 3) {
                        dX--;
                    }

                    if (pX > rectMaxX) {
                        break;
                    }

                    if (pX + dX >= 0) {

                        // Test to see if any single nucleotide mismatch  (nucleotide other than the reference)
                        // has a quality weight > 20% of the total
                        // Skip this test if the position is in the list of known snps or if the reference is unknown
                        boolean mismatch = false;
                        char ref = 0;
                        if (refBases != null) {
                            int idx = pos - intervalStart;
                            if (idx >= 0 && idx < refBases.length) {
                                ref = Character.toLowerCase((char) refBases[idx]);
                                mismatch = alignmentCounts.isMismatch(pos, ref, context.getChr(), snpThreshold);
                            }
                        }


                        if (pX > lastpX || mismatch) {

                            boolean strandOption = false;

                            if (strandOption) {

                                // Negative strand
                                int pY = (int) (rectY + rectMaxY) / 2;
                                int totalNegCount = alignmentCounts.getNegTotal(pos);
                                int height = (int) (totalNegCount * rectHeight / colorScaleMax);
                                height = Math.min(height, rect.height / 2 - 1);
                                if (height > 0) {
                                    negGraphics.fillRect(pX, pY, dX, height);
                                    if (mismatch) {
                                        for (char c : nucleotides) {
                                            if (c != ref) {
                                                pY = drawStrandBar(context, pos, rect, colorScaleMax, pY, pX, dX, c,
                                                        false, alignmentCounts);
                                            }
                                        }
                                    }
                                }

                                // Positive strand
                                pY = (int) (rectY + rectMaxY) / 2;
                                int totalPosCount = alignmentCounts.getPosTotal(pos);
                                height = (int) (totalPosCount * rectHeight / colorScaleMax);
                                height = Math.min(height, rect.height / 2 - 1);
                                int topY = (pY - height);
                                if (height > 0) {
                                    posGraphics.fillRect(pX, topY, dX, height);
                                    if (mismatch) {
                                        for (char c : nucleotides) {
                                            if (c != ref) {
                                                pY = drawStrandBar(context, pos, rect, colorScaleMax, pY, pX, dX, c,
                                                        true, alignmentCounts);
                                            }
                                        }
                                    }
                                }

                                // Center line
                                pY = (int) (rectY + rectMaxY) / 2;
                                Graphics2D blackGraphics = context.getGraphic2DForColor(lightBlue);
                                blackGraphics.drawLine(0, pY, rect.width, pY);

                            } else {

                                int pY = (int) rectMaxY - 1;

                                int totalCount = alignmentCounts.getTotalCount(pos);

                                double tmp = range.isLog() ? Math.log10(totalCount) / max : totalCount / max;
                                int height = (int) (tmp * rectHeight);

                                height = Math.min(height, rect.height - 1);
                                int topY = (pY - height);

                                if (height > 0) {
                                    graphics.fillRect(pX, topY, dX, height);

                                    if (mismatch) {
                                        for (char c : nucleotides) {
                                            pY = drawBar(context, pos, rect, totalCount, max,
                                                    pY, pX, dX, c, alignmentCounts, range.isLog());
                                        }

                                    }
                                }
                            }
                            lastpX = pX;
                        }
                    }
                }
            }
        }

        /**
         * Draw a colored bar to represent a mismatch to the reference.   The height is proportional to the % of
         * reads with respect to the total.  If "showAllSnps == true"  the bar is shaded by avg read quality.
         *
         * @param context
         * @param pos
         * @param rect
         * @param max
         * @param pY
         * @param pX
         * @param dX
         * @param nucleotide
         * @param interval
         * @return
         */

        int drawBar(RenderContext context,
                    int pos,
                    Rectangle rect,
                    double totalCount,
                    double max,
                    int pY,
                    int pX,
                    int dX,
                    char nucleotide,
                    AlignmentCounts interval,
                    boolean isLog) {

            int count = interval.getCount(pos, (byte) nucleotide);

            Color c = AlignmentRenderer.getNucleotideColors().get(nucleotide);

            Graphics2D tGraphics = context.getGraphic2DForColor(c);

            double tmp = isLog ?
                    (count / totalCount) * Math.log10(totalCount) / max :
                    count / max;
            int height = (int) (tmp * rect.getHeight());

            height = Math.min(pY - rect.y, height);
            int baseY = pY - height;

            if (height > 0) {
                tGraphics.fillRect(pX, baseY, dX, height);
            }
            return baseY;
        }


        /**
         * Strand-specific
         *
         * @param context
         * @param pos
         * @param rect
         * @param maxCount
         * @param pY
         * @param pX
         * @param dX
         * @param nucleotide
         * @param isPositive
         * @param interval
         * @return
         */
        int drawStrandBar(RenderContext context,
                          int pos,
                          Rectangle rect,
                          double maxCount,
                          int pY,
                          int pX,
                          int dX,
                          char nucleotide,
                          boolean isPositive,
                          AlignmentCounts interval) {


            Color c = AlignmentRenderer.getNucleotideColors().get(nucleotide);
            Graphics2D tGraphics = context.getGraphic2DForColor(c);


            int count = isPositive ? interval.getPosCount(pos, (byte) nucleotide) :
                    interval.getNegCount(pos, (byte) nucleotide);

            int height = (int) Math.round(count * rect.getHeight() / maxCount);
            height = isPositive ? Math.min(pY - rect.y, height) :
                    Math.min(rect.y + rect.height - pY, height);
            int baseY = (int) (isPositive ? (pY - height) : pY);

            if (height > 0) {
                tGraphics.fillRect(pX, baseY, dX, height);
            }
            return isPositive ? baseY : baseY + height;
        }

    }


    static float[] colorComps = new float[3];

    private Color getShadedColor(int qual, Color backgroundColor, Color color) {
        float alpha = 0;
        int minQ = prefs.getAsInt(PreferenceManager.SAM_BASE_QUALITY_MIN);
        ColorUtilities.getRGBColorComponents(color);
        if (qual < minQ) {
            alpha = 0.1f;
        } else {
            int maxQ = prefs.getAsInt(PreferenceManager.SAM_BASE_QUALITY_MAX);
            alpha = Math.max(0.1f, Math.min(1.0f, 0.1f + 0.9f * (qual - minQ) / (maxQ - minQ)));
        }
        // Round alpha to nearest 0.1, for effeciency;
        alpha = ((int) (alpha * 10 + 0.5f)) / 10.0f;

        if (alpha >= 1) {
            return color;
        } else {
            return ColorUtilities.getCompositeColor(backgroundColor, color, alpha);
        }
    }


    /**
     * Called by session writer.  Return instance variable values as a map of strings.  Used to record current state
     * of object.   Variables with default values are not stored, as it is presumed the user has not changed them.
     *
     * @return
     */
    @Override
    public Map<String, String> getPersistentState() {
        Map<String, String> attributes = super.getPersistentState();
        prefs = PreferenceManager.getInstance();
        if (snpThreshold != prefs.getAsFloat(PreferenceManager.SAM_ALLELE_THRESHOLD)) {
            attributes.put("snpThreshold", String.valueOf(snpThreshold));
        }
        attributes.put("autoScale", String.valueOf(autoScale));
        if (showReference != DEFAULT_SHOW_REFERENCE) {
            attributes.put("showReference", String.valueOf(showReference));
        }

        return attributes;
    }

    /**
     * Called by session reader.  Restores state of object.
     *
     * @param attributes
     */
    @Override
    public void restorePersistentState(Map<String, String> attributes) {
        super.restorePersistentState(attributes);    //To change body of overridden methods use File | Settings | File Templates.

        String value;
        value = attributes.get("snpThreshold");
        if (value != null) {
            snpThreshold = Float.parseFloat(value);
        }
        value = attributes.get("autoScale");
        if (value != null) {
            autoScale = Boolean.parseBoolean(value);
        }
        value = attributes.get("showReference");
        if (value != null) {
            showReference = Boolean.parseBoolean(value);
        }
    }

    /**
     * Override to return a specialized popup menu
     *
     * @return
     */
    @Override
    public IGVPopupMenu getPopupMenu(TrackClickEvent te) {

        IGVPopupMenu popupMenu = new IGVPopupMenu();

        JLabel popupTitle = new JLabel("  " + getName(), JLabel.CENTER);

        Font newFont = popupMenu.getFont().deriveFont(Font.BOLD, 12);
        popupTitle.setFont(newFont);
        if (popupTitle != null) {
            popupMenu.add(popupTitle);
        }

        popupMenu.addSeparator();

        // addSortMenuItem(popupMenu);
        // addPackMenuItem(popupMenu);
        // addShadeBaseMenuItem(popupMenu);
        // addCopyToClipboardItem(popupMenu, evt);
        // addGoToMate(popupMenu, evt);
        // popupMenu.addSeparator();


        //JLabel trackSettingsHeading = new JLabel("  Track Settings",
        //        JLabel.LEFT);
        //trackSettingsHeading.setFont(newFont);

        //popupMenu.add(trackSettingsHeading);

        ArrayList<Track> tmp = new ArrayList();
        tmp.add(this);
        popupMenu.add(TrackMenuUtils.getTrackRenameItem(tmp));


        addAutoscaleItem(popupMenu);
        addLogScaleItem(popupMenu);
        dataRangeItem = addDataRangeItem(popupMenu, tmp);
        dataRangeItem.setEnabled(!autoScale);

        this.addSnpTresholdItem(popupMenu);

        popupMenu.addSeparator();
        addLoadCoverageDataItem(popupMenu);
        popupMenu.addSeparator();

        popupMenu.add(TrackMenuUtils.getRemoveMenuItem(tmp));

        return popupMenu;
    }


    public JMenuItem addDataRangeItem(JPopupMenu menu, final Collection<Track> selectedTracks) {
        JMenuItem maxValItem = new JMenuItem("Set Data Range");

        maxValItem.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent e) {
                if (selectedTracks.size() > 0) {

                    DataRange prevAxisDefinition = selectedTracks.iterator().next().getDataRange();
                    DataRangeDialog dlg = new DataRangeDialog(IGV.getMainFrame(), prevAxisDefinition);
                    dlg.setHideMid(true);
                    dlg.setVisible(true);
                    if (!dlg.isCanceled()) {
                        float min = Math.min(dlg.getMin(), dlg.getMax());
                        float max = Math.max(dlg.getMin(), dlg.getMax());
                        float mid = dlg.getBase();
                        if (mid < min) mid = min;
                        else if (mid > max) mid = max;
                        DataRange dataRange = new DataRange(min, mid, max);
                        dataRange.setType(getDataRange().getType());

                        // dlg.isFlipAxis());
                        for (Track track : selectedTracks) {
                            track.setDataRange(dataRange);
                        }
                        IGV.getMainFrame().repaint();
                    }
                }

            }
        });
        menu.add(maxValItem);

        return maxValItem;
    }

    public JMenuItem addSnpTresholdItem(JPopupMenu menu) {
        JMenuItem maxValItem = new JMenuItem("Set allele frequency threshold...");

        maxValItem.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent e) {

                String value = JOptionPane.showInputDialog("Allele frequency threshold: ", Float.valueOf(snpThreshold));
                if (value == null) {
                    return;
                }
                try {
                    float tmp = Float.parseFloat(value);
                    snpThreshold = tmp;
                    IGV.getInstance().repaintDataPanels();
                } catch (Exception exc) {
                    //log
                }

            }
        });
        menu.add(maxValItem);

        return maxValItem;
    }

    public void addLoadCoverageDataItem(JPopupMenu menu) {
        // Change track height by attribute
        final JMenuItem item = new JCheckBoxMenuItem("Load coverage data...");
        item.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent e) {

                final PreferenceManager prefs = PreferenceManager.getInstance();
                File initDirectory = prefs.getLastTrackDirectory();
                File file = FileDialogUtils.chooseFile("Select coverage file", initDirectory, FileDialog.LOAD);
                if (file != null) {
                    prefs.setLastTrackDirectory(file.getParentFile());
                    String path = file.getAbsolutePath();
                    if (path.endsWith(".tdf") || path.endsWith(".tdf")) {
                        TDFReader reader = TDFReader.getReader(file.getAbsolutePath());
                        TDFDataSource ds = new TDFDataSource(reader, 0, getName() + " coverage", genome);
                        setDataSource(ds);
                        IGV.getInstance().repaintDataPanels();
                    } else if (path.endsWith(".counts")) {
                        DataSource ds = new GobyCountArchiveDataSource(file);
                        setDataSource(ds);
                        IGV.getInstance().repaintDataPanels();
                    } else {
                        MessageUtils.showMessage("Coverage data must be in .tdf format");
                    }
                }
            }
        });

        menu.add(item);

    }


    public void addAutoscaleItem(JPopupMenu menu) {
        // Change track height by attribute
        autoscaleItem = new JCheckBoxMenuItem("Autoscale");
        autoscaleItem.setSelected(autoScale);
        autoscaleItem.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent e) {

                autoScale = autoscaleItem.isSelected();
                dataRangeItem.setEnabled(!autoScale);
                if (autoScale) {
                    rescale();
                }
                IGV.getInstance().repaintDataPanels();

            }
        });

        menu.add(autoscaleItem);
    }

    public void addLogScaleItem(JPopupMenu menu) {
        // Change track height by attribute
        final DataRange dataRange = getDataRange();
        final JCheckBoxMenuItem logScaleItem = new JCheckBoxMenuItem("Log scale");
        final boolean logScale = dataRange.getType() == DataRange.Type.LOG;
        logScaleItem.setSelected(logScale);
        logScaleItem.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent e) {

                DataRange.Type scaleType = logScaleItem.isSelected() ?
                        DataRange.Type.LOG :
                        DataRange.Type.LINEAR;
                dataRange.setType(scaleType);
                IGV.getInstance().repaintDataPanels();
            }
        });

        menu.add(logScaleItem);
    }

}
