import ij.IJ;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;
import ij.util.Tools;
import ij.gui.NonBlockingGenericDialog;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.gui.Toolbar;
import ij.measure.ResultsTable;
import ij.gui.Overlay;
import ij.gui.Plot;
import ij.gui.PlotWindow;
import ij.gui.PointRoi;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.gui.Line;

import org.apache.commons.math3.stat.regression.SimpleRegression;

import java.awt.AWTEvent;
import java.awt.Point;
import java.awt.TextField;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

public class Neck_Profilor implements PlugIn {

    protected ImagePlus image;
    protected int imageZ;

    protected Roi cyst1neckLine = null;
    protected Roi cyst2neckLine = null;

    protected PlotWindow previewWindow;
    protected NonBlockingGenericDialog controlDialog;

    protected double[] positions;
    protected double[] distances;

    protected String savePath;

    @Override
    public void run(String arg) {
        IJ.setTool(Toolbar.FREELINE);

        controlDialog = new NonBlockingGenericDialog("Neck Profilor controls");
        controlDialog.addButton("Save ROI as cyst 1 neck line", new ActionDrawCyst1());
        controlDialog.addButton("Save ROI as cyst 2 neck line", new ActionDrawCyst2());
        controlDialog.addButton("Measure interface thickness and preview", new ActionMeasure());
        controlDialog.addButton("Update save path based on image metadata", new ActionUpdateSavePath());
        controlDialog.addFileField("Save path", "", 30);
        controlDialog.addButton("Save", new ActionSave());
        controlDialog.addDialogListener(new DialogChangedListener());
        controlDialog.hideCancelButton();
        controlDialog.setOKLabel("Quit");
        controlDialog.showDialog();
    }

    public void setCyst1neckLine() {
        image = IJ.getImage();
        imageZ = image.getZ();
        positions = null;
        distances = null;
        if (image == null) {
            IJ.noImage();
            return;
        }
        cyst1neckLine = setCystNeckLine("NECK_PROFILOR|CYST1");
    }

    public void setCyst2neckLine() {
        ImagePlus thisImage = IJ.getImage();
        if (thisImage == null) {
            IJ.noImage();
            return;
        }
        int thisZ = thisImage.getZ();
        positions = null;
        distances = null;
        if (image != thisImage) {
            IJ.error("Image for cyst 2 is not the same as cyst 1");
            return;
        }
        if (imageZ != thisZ) {
            IJ.error("Z position for cyst 2 is not the same as cyst 1");
            return;
        }
        cyst2neckLine = setCystNeckLine("NECK_PROFILOR|CYST2");
    }

    public Roi setCystNeckLine(String roiName) {
        Roi roi = image.getRoi();
        if (roi == null) {
            IJ.error("No ROI");
            return null;
        }
        roi = (Roi) roi.clone();
        addRoiToImage(roi, roiName, true);
        return roi;
    }

    public SimpleRegression linearRegression(Point[] points) {
        SimpleRegression lineRegression = new SimpleRegression(true);
        for (int i = 0; i < points.length; i++) {
            lineRegression.addData(points[i].getX(), points[i].getY());
        }
        return lineRegression;
    }

    public boolean cystsAreSet() {
        return this.cyst1neckLine != null && this.cyst2neckLine != null;
    }

    public double[] getMidLine(Point[] cloudPoints1, Point[] cloudPoints2) {
        SimpleRegression reg1 = linearRegression(cloudPoints1);
        SimpleRegression reg2 = linearRegression(cloudPoints2);
        double a1 = reg1.getSlope();
        double a2 = reg2.getSlope();
        double b1 = reg1.getIntercept();
        double b2 = reg2.getIntercept();
        double a = (a1 + a2) / 2.0;
        double b = (b1 + b2) / 2.0;
        return new double[] {a, b};
    }

    public Map<Point, ArrayList<Point>> projectOnLine(Point[] points, double a, double b) {
        Map<Point, ArrayList<Point>> land = new HashMap<>();
        for (int i = 0; i < points.length; i++) {
            Point point = points[i];
            double x = point.getX();
            double y = point.getY();
            double x1 = ((x + a*y) - a*b) / (a*a + 1);
            double y1 = (a*(x + a*y) + b) / (a*a + 1);
            Point landingPoint = new Point((int) Math.rint(x1), (int) Math.rint(y1));
            land.computeIfAbsent(landingPoint, k -> new ArrayList<>());
            land.get(landingPoint).add(point);
        }
        return land;
    }

    public double[] findExtremeXY(Point[] points) {
        double xmin = Double.POSITIVE_INFINITY;
        double xmax = Double.NEGATIVE_INFINITY;
        double ymin = Double.POSITIVE_INFINITY;
        double ymax = Double.NEGATIVE_INFINITY;
        for (Point p : points) {
            xmin = Math.min(xmin, p.getX());
            ymin = Math.min(ymin, p.getY());
            xmax = Math.max(xmax, p.getX());
            ymax = Math.max(ymax, p.getY());
        }
        return new double[] {xmin, xmax, ymin, ymax};
    }

    public void addRoiToImage(Roi roi, String roiName, boolean removeIfExists) {
        Overlay overlay = image.getOverlay();
        if (overlay == null)
            overlay = new Overlay();
        if (removeIfExists)
            overlay.remove(roiName);
        overlay.add(roi, roiName);
        image.setOverlay(overlay);
        image.updateAndDraw();
    }

    /**
     * Measure the average position of a cloud of points
     */
    public Point measureAveragePosition(Point[] pointCloud) {
        double x = 0;
        double y = 0;
        for (int i = 0; i < pointCloud.length; i++) {
            x += pointCloud[i].getX() / pointCloud.length;
            y += pointCloud[i].getY() / pointCloud.length;
        }
        return new Point((int) Math.rint(x), (int) Math.rint(y));
    }

    public void measure() {
        if (!cystsAreSet()) {
            IJ.error("Cysts ROIs are not set yet");
            return;
        }

        // Get points on the cyst ROIs
        Point[] points1 = cyst1neckLine.getContainedPoints();
        Point[] points2 = cyst2neckLine.getContainedPoints();

        // Get the mid line, ie the line that goes in the middle of the two point clouds
        double[] midLineParams = getMidLine(points1, points2);
        double a = midLineParams[0];
        double b = midLineParams[1];

        // Find the points on the mid line, that correspond to orthogonal projections of
        // the point clouds on the mid line
        Map<Point, ArrayList<Point>> land1 = projectOnLine(points1, a, b);
        Map<Point, ArrayList<Point>> land2 = projectOnLine(points2, a, b);

        // Segment the mid line, restricted to the area where point clouds project
        double[] extremeXY1 = findExtremeXY(land1.keySet().toArray(new Point[0]));
        double[] extremeXY2 = findExtremeXY(land2.keySet().toArray(new Point[0]));
        double xmin = Math.max(extremeXY1[0], extremeXY2[0]);
        double xmax = Math.min(extremeXY1[1], extremeXY2[1]);
        double ymin = Math.max(extremeXY1[2], extremeXY2[2]);
        double ymax = Math.min(extremeXY1[3], extremeXY2[3]);

        double xLine0 = xmin;
        double xLine1 = xmax;
        double yLine0 = a*xmin+b;
        double yLine1 = a*xmax+b;

        // Draw the mid line segment and extreme points
        addRoiToImage(new Line(xLine0, yLine0, xLine1, yLine1), "NECK_PROFILOR|MIDLINE", true);
        addRoiToImage(new PointRoi(xmin, ymin), "NECK_PROFILOR|xyMIN", true);
        addRoiToImage(new PointRoi(xmax, ymax), "NECK_PROFILOR|xyMAX", true);
        addRoiToImage(new PointRoi(xLine0, yLine0), "NECK_PROFILOR|xyLineStart", true);

        // Retain land points that correspond to both point clouds
        // (we will pair points from the clouds based on their landing point)
        Set<Point> landPointsSet = new HashSet<>(land1.keySet());
        landPointsSet.retainAll(land2.keySet());
        Point[] landPoints = landPointsSet.toArray(new Point[0]);

        distances = new double[landPoints.length];
        positions = new double[landPoints.length];
        for (int i = 0; i < landPoints.length; i++) {
            Point landPoint = landPoints[i];
            double x = landPoint.getX();
            double y = landPoint.getY();
            if (x < xmin || x > xmax || y < ymin || y > ymax)
                continue;
            Point p1 = measureAveragePosition(land1.get(landPoint).toArray(new Point[0]));
            Point p2 = measureAveragePosition(land2.get(landPoint).toArray(new Point[0]));
            double x1 = p1.getX();
            double y1 = p1.getY();
            double x2 = p2.getX();
            double y2 = p2.getY();
            double d1 = Math.sqrt(Math.pow(x1-x, 2) + Math.pow(y1-y, 2));
            double d2 = Math.sqrt(Math.pow(x2-x, 2) + Math.pow(y2-y, 2));
            double dot = (x1-x)*(x2-x) + (y1-y)*(y2-y);
            double sign = Math.signum(dot);
            distances[i] = (d1 - sign*d2) * image.getCalibration().pixelWidth;
            positions[i] = Math.sqrt(Math.pow(x-xLine0, 2) + Math.pow(y-yLine0, 2)) * image.getCalibration().pixelWidth;
        }

        Plot previewPlot = new Plot("Neck Profilor preview", "Position", "Neck thickness");
        previewPlot.addPoints(positions, distances, Plot.CIRCLE);
        previewPlot.update();
        if (previewWindow == null)
            previewWindow = previewPlot.show();
        previewWindow.drawPlot(previewPlot);
        previewWindow.addWindowListener(new PlotWindowListener());
    }

    public void save() {
        if (distances == null || positions == null) {
            IJ.error("Not measured yet...");
            return;
        }
        ResultsTable table = new ResultsTable();
        for (int i = 0; i < positions.length; i++) {
            table.addRow();
            table.addValue("Position", positions[i]);
            table.addValue("Neck thickness", distances[i]);
        }
        table.save(savePath + ".neck_profile_z=" + imageZ + ".csv");
        RoiManager rm = new RoiManager(false);
        rm.addRoi(cyst1neckLine);
        rm.addRoi(cyst2neckLine);
        rm.save(savePath + ".neck_profile_z=" + imageZ + ".roi.zip");
        rm.close();
    }

    class ActionDrawCyst1 implements ActionListener {
        public void actionPerformed(ActionEvent e) {
            setCyst1neckLine();
        }
    }

    class ActionDrawCyst2 implements ActionListener {
        public void actionPerformed(ActionEvent e) {
            setCyst2neckLine();
        }
    }

    class ActionMeasure implements ActionListener {
        public void actionPerformed(ActionEvent e) {
            measure();
        }
    }

    class ActionSave implements ActionListener {
        public void actionPerformed(ActionEvent e) {
            save();
        }
    }

    class DialogChangedListener implements DialogListener {
        public boolean dialogItemChanged(GenericDialog gd, AWTEvent e) {
            savePath = gd.getNextString();
            return true;
        }
    }

    class ActionUpdateSavePath implements ActionListener {
        public void actionPerformed(ActionEvent e) {
            if (!cystsAreSet()) {
                IJ.error("Cysts ROIs are not set yet");
                return;
            }
            TextField savePathField = (TextField) controlDialog.getStringFields().get(0);
            savePathField.setText(image.getOriginalFileInfo().getFilePath());
        }
    }

    class PlotWindowListener implements WindowListener {
        public void windowOpened(WindowEvent e) {
        }
        public void windowClosing(WindowEvent e) {
        }
        public void windowClosed(WindowEvent e) {
            previewWindow = null;
        }
        public void windowIconified(WindowEvent e) {
        }
        public void windowDeiconified(WindowEvent e) {
        }
        public void windowActivated(WindowEvent e) {
        }
        public void windowDeactivated(WindowEvent e) {
        }
    }
}
