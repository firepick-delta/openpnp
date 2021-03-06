package org.openpnp.vision;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.openpnp.model.Board;
import org.openpnp.model.Board.Side;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Length;
import org.openpnp.model.Location;
import org.openpnp.model.Placement;
import org.openpnp.model.Placement.Type;
import org.openpnp.spi.Camera;
import org.openpnp.spi.VisionProvider;
import org.openpnp.spi.VisionProvider.TemplateMatch;
import org.openpnp.util.IdentifiableList;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.Utils2D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements an algorithm for finding a set of fiducials on a board and
 * returning the correct orientation for the board. 
 * TODO: This needs to be integrated into the VisionProvider somehow.
 */
public class FiducialLocator {
    private static final Logger logger = LoggerFactory
            .getLogger(FiducialLocator.class);
    
    public FiducialLocator() {
        
    }
    
    public Location locateBoard(BoardLocation boardLocation) throws Exception {
        // TODO: finish bottom code
        
        // Find the fids in the board
        IdentifiableList<Placement> fiducials = getFiducials(boardLocation);
        
        if (fiducials.size() < 2) {
            throw new Exception(
                String.format(
                    "The board side contains only %d placements marked as fiducials, but at least 2 are required.",
                    fiducials.size())); 
        }
        
        // Find the two that are most distant from each other
        List<Placement> mostDistant = getMostDistantPlacements(fiducials);
        
        Placement a = mostDistant.get(0);
        Placement b = mostDistant.get(1);

        logger.debug("Chose {} and {}", a.getId(), b.getId());
        
        Location aLoc = a.getLocation();
        Location bLoc = b.getLocation();
                
        if (boardLocation.getSide() == Side.Bottom) {
            aLoc = aLoc.invert(true, false, false, false);
            bLoc = bLoc.invert(true, false, false, false);
        }

        // Run the fiducial check on each and get their actual locations
        Location aVisionLoc = getFiducialLocation(boardLocation, a);
        if (aVisionLoc == null) {
            throw new Exception("Unable to locate first fiducial.");
        }
        Location bVisionLoc = getFiducialLocation(boardLocation, b);
        if (bVisionLoc == null) {
            throw new Exception("Unable to locate second fiducial.");
        }
        
        // Calculate the angle and offset from the results
        Location location = Utils2D.calculateAngleAndOffset(
                a.getLocation(), 
                b.getLocation(), 
                aVisionLoc,
                bVisionLoc);
        
        location = location.derive(
                null, 
                null, 
                boardLocation.getLocation().convertToUnits(location.getUnits()).getZ(), 
                null);

        return location;
    }
    
    /**
     * Given a placement containing a fiducial, attempt to find the fiducial
     * using the vision system. The function first moves the camera to the
     * ideal location of the fiducial based on the board location. It then
     * performs a template match against a template generated from the
     * fiducial's footprint. These steps are performed thrice to "home in"
     * on the fiducial. Finally, the location is returned. If the fiducial
     * was not able to be located with any degree of certainty the function
     * returns null.
     * @param fid
     * @return
     * @throws Exception
     */
    private static Location getFiducialLocation(BoardLocation boardLocation, Placement fid) throws Exception {
        Camera camera = Configuration.get().getMachine().getHeads().get(0).getCameras().get(0);
        
        logger.debug("Locating {}", fid.getId());
        
        // Create the template
        BufferedImage template = createTemplate(camera.getUnitsPerPixel(), fid);
        
        // Move to where we expect to find the fid
        Location location = Utils2D.calculateBoardPlacementLocation(
                boardLocation.getLocation(), boardLocation.getSide(),
                fid.getLocation());
        MovableUtils.moveToLocationAtSafeZ(camera, location, 1.0);

        
        // these modifications make the template match more accurate but have only
        // been tested slightly
//      Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2GRAY);
//      Imgproc.GaussianBlur(mat, mat, new Size(3, 3), 0);
//        Imgproc.threshold(mat, mat, 100, 255, Imgproc.THRESH_BINARY);
        
        
        
        for (int i = 0; i < 3; i++) {
            // Wait for camera to settle
            Thread.sleep(500);
            // Perform vision operation
            location = getBestTemplateMatch(camera, template);
            if (location == null) {
                logger.debug("No matches found!");
                return null;
            }
            logger.debug("{} located at {}", fid.getId(), location);
            // Move to where we actually found the fid
            MovableUtils.moveToLocationAtSafeZ(camera, location, 1.0);        
        }
        
        return location;
    }
    
    private static Location getBestTemplateMatch(final Camera camera, BufferedImage template) throws Exception {
        VisionProvider visionProvider = camera.getVisionProvider();
        
        List<TemplateMatch> matches = visionProvider.getTemplateMatches(template);
        
        if (matches.isEmpty()) {
            return null;
        }
        
        // getTemplateMatches returns results in order of score, but we're
        // more interested in the result closest to the expected location
        Collections.sort(matches, new Comparator<TemplateMatch>() {
            @Override
            public int compare(TemplateMatch o1, TemplateMatch o2) {
                double d1 = o1.location.getLinearDistanceTo(camera.getLocation()); 
                double d2 = o2.location.getLinearDistanceTo(camera.getLocation()); 
                return Double.compare(d1, d2);
            }
        });

        return matches.get(0).location;
    }
    
    /**
     * Create a template image based on a Placement's footprint. The image
     * will be scaled to match the dimensions of the current camera.
     * @param fid
     * @return
     */
    private static BufferedImage createTemplate(Location unitsPerPixel, Placement fid) {
        Footprint footprint = fid.getPart().getPackage().getFootprint();
        Shape shape = footprint.getShape();
        
        // Determine the scaling factor to go from Outline units to
        // Camera units.
        Length l = new Length(1, footprint.getUnits());
        l = l.convertToUnits(unitsPerPixel.getUnits());
        double unitScale = l.getValue();

        // Create a transform to scale the Shape by
        AffineTransform tx = new AffineTransform();
        
        // First we scale by units to convert the units and then we scale
        // by the camera X and Y units per pixels to get pixel locations.
        tx.scale(unitScale, unitScale);
        tx.scale(1.0 / unitsPerPixel.getX(), 1.0 / unitsPerPixel.getY());
        
        // Transform the Shape and draw it out.
        shape = tx.createTransformedShape(shape);
        
        Rectangle2D bounds = shape.getBounds2D();
        
        // Make the image 50% bigger than the shape. This gives better
        // recognition performance because it allows some border around the edges.
        double width = bounds.getWidth() * 1.5;
        double height = bounds.getHeight() * 1.5;
        BufferedImage template = new BufferedImage((int) width, (int) height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = (Graphics2D) template.getGraphics();
        
        g2d.setStroke(new BasicStroke(1f));
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setColor(Color.white);
        // center the drawing
        g2d.translate(width / 2, height / 2);
        g2d.fill(shape);
        
        g2d.dispose();
        
        return template;
    }

    /**
     * Given a List of Placements, find the two that are the most distant from
     * each other.
     * @param fiducials
     * @return
     */
    private static List<Placement> getMostDistantPlacements(List<Placement> fiducials) {
        if (fiducials.size() < 2) {
            return null;
        }
        Placement maxA = null, maxB = null;
        double max = 0;
        for (Placement a : fiducials) {
            for (Placement b : fiducials) {
                if (a == b) {
                    continue;
                }
                double d = Math.abs(a.getLocation().getLinearDistanceTo(b.getLocation()));
                if (d > max) {
                    maxA = a;
                    maxB = b;
                    max = d;
                }
            }
        }
        ArrayList<Placement> results = new ArrayList<Placement>();
        results.add(maxA);
        results.add(maxB);
        return results;
    }
    
    private static IdentifiableList<Placement> getFiducials(BoardLocation boardLocation) {
        Board board = boardLocation.getBoard();
        IdentifiableList<Placement> fiducials = new IdentifiableList<Placement>();
        for (Placement placement : board.getPlacements()) {
            if (placement.getType() == Type.Fiducial
                    && placement.getSide() == boardLocation.getSide()) {
                fiducials.add(placement);
            }
        }
        return fiducials;
    }
}
