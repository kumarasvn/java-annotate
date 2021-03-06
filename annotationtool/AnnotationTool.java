package annotationtool;

import java.awt.AWTEvent;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Paint;
import java.awt.Point;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import sun.awt.image.ToolkitImage;

public class AnnotationTool extends JFrame {

    private class ShapeDef {

        Shape shape;
        Paint paint;
        Stroke stroke;

        ShapeDef(Stroke stroke, Paint paint, Shape shape) {
            this.stroke = stroke;
            this.paint = paint;
            this.shape = shape;
        }
    }

    private Image backingMain;
    private Image backingScratch;
    private static Color clearPaint = new Color(0, 0, 0, 0);

    private Paint paint;
    private Stroke stroke;

    private ShapeDef blockOutShapeDef;
    private ShapeDef border;

    private Deque<ShapeDef> undoStack = new ArrayDeque<ShapeDef>();
    private Deque<ShapeDef> redoStack = new ArrayDeque<ShapeDef>();

    private Cursor defaultCursor;
    private Cursor pencilCursor;

    private int saveImageIndex = 0;

    public AnnotationTool(int x, int y, int w, int h) {

        super("Drawing Frame");
        setUndecorated(true);

        Toolkit toolkit = Toolkit.getDefaultToolkit();

        try {
            InputStream imageStream = this.getClass().getResourceAsStream("pencil-32.png");
            System.out.println("Stream is " + imageStream);
            Image image = ImageIO.read(imageStream);
            pencilCursor = toolkit.createCustomCursor(image, new Point(0, 26), "pencil");
            defaultCursor = getCursor();
//            setCursor(pencilCursor);
        } catch (IOException ioe) {
            ioe.printStackTrace(System.err);
        }

        setBounds(x - 5, y - 5, w + 10, h + 10);

        Stroke blockOutStroke;
        Path2D.Float blockOutShape;
        blockOutStroke = new BasicStroke(h, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_BEVEL);
        blockOutShape = new Path2D.Float();
        blockOutShape.moveTo(0, h / 2);
        blockOutShape.lineTo(w, h / 2);
        blockOutShapeDef = new ShapeDef(blockOutStroke, clearPaint, blockOutShape);

        // make the window transparent
        setBackground(clearPaint);

        setPreferredSize(new Dimension(w + 10, h + 10));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        enableEvents(AWTEvent.KEY_EVENT_MASK
                + AWTEvent.MOUSE_EVENT_MASK
                + AWTEvent.MOUSE_MOTION_EVENT_MASK);
        setVisible(true);

        backingMain = createImage(w, h);
        backingScratch = createImage(w, h);

        Path2D.Float borderShape = new Path2D.Float();
        borderShape.moveTo(0, 0);
        borderShape.lineTo(w + 10, 0);
        borderShape.lineTo(w + 10, h + 10);
        borderShape.lineTo(0, h + 10);
        borderShape.closePath();
        border = new ShapeDef(
                new BasicStroke(10, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER),
                new Color(255, 128, 0, 255),
                borderShape);
    }

    public void setPaint(Paint paint) {
        this.paint = paint;
    }

    public void setStroke(Stroke stroke) {
        this.stroke = stroke;
    }

    public void doClear(Paint paint) {
        blockOutShapeDef.paint = paint;
        commitShape(blockOutShapeDef);
        repaint();
    }

    public void doClear() {
        doClear(clearPaint);
    }

    public void clearHistory() {
        doClear();
        undoStack.clear();
        redoStack.clear();
    }

    final ClipboardOwner clipboardOwner = new ClipboardOwner() {
        @Override
        public void lostOwnership(Clipboard clipboard, Transferable contents) {
        }
    };

    public void doSave() {
        // find filename for use
        File outFile;
        String fname;
        do {
            fname = String.format("image-%06d.png", saveImageIndex++);
            System.out.println("Trying " + fname);
            outFile = new File(fname);
        } while (outFile.exists());

        String imageTag = "<img src='" + fname +"'>";
        Clipboard clip = this.getToolkit().getSystemClipboard();
        clip.setContents(new StringSelection(imageTag), clipboardOwner);
        System.out.println(imageTag);
        
        try {
            BufferedImage outImg = null;
            if (backingMain instanceof BufferedImage) {
                outImg = (BufferedImage) backingMain;
            } else if (backingMain instanceof ToolkitImage) {
                outImg = ((ToolkitImage) backingMain).getBufferedImage();
            } else {
                System.err.println("Hmm, not one of those two...");
            }

            ImageIO.write(outImg, "png", outFile);
        } catch (IOException ex) {
            System.err.println("Save failed: " + ex.getMessage());
        }
    }

    @Override
    public void paint(Graphics graphics) {
        // Blank out the scratch image
        Graphics2D gScratch = (Graphics2D) backingScratch.getGraphics();
        gScratch.setComposite(AlphaComposite.Src);
        gScratch.setBackground(clearPaint);
        gScratch.clearRect(0, 0, this.getBounds().width, this.getBounds().height);
        gScratch.drawImage(backingMain, 0, 0, null);

        // if there is a "shape in progress" draw it on the scratch image
        if (p2d != null) {
            gScratch.setPaint(paint);
            gScratch.setStroke(stroke);
            gScratch.draw(p2d);
        }

        Graphics2D g = (Graphics2D) graphics;
        g.setComposite(AlphaComposite.Src);
        AffineTransform trans = g.getTransform();
        g.translate(5, 5);
        g.drawImage(backingScratch, 0, 0, null);
        g.setTransform(trans);
        g.setPaint(border.paint);
        g.setStroke(border.stroke);
        g.draw(border.shape);
    }

    private Path2D.Float p2d; // shape in progress...

    public void undo() {
        if (undoStack.size() > 0) {
            ShapeDef sd = undoStack.pop();
            redoStack.push(sd);
            paintFromUndoStack();
        }
    }

    public void redo() {
        if (redoStack.size() > 0) {
            ShapeDef sd = redoStack.pop();
            undoStack.push(sd);
            paintFromUndoStack();
        }
    }

    private void paintFromUndoStack() {
        Graphics2D g = (Graphics2D) backingMain.getGraphics();
        g.setBackground(clearPaint);
        g.setComposite(AlphaComposite.Src);
        g.clearRect(0, 0, this.getBounds().width, this.getBounds().height);
        Iterator<ShapeDef> sdi = undoStack.descendingIterator();
        while (sdi.hasNext()) {
            ShapeDef s = sdi.next();
            g.setPaint(s.paint);
            g.setStroke(s.stroke);
            g.draw(s.shape);
        }
        repaint();
    }

    private void commitShape(ShapeDef s) {
        undoStack.push(s);
        Graphics2D g = (Graphics2D) backingMain.getGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setPaint(s.paint);
        g.setStroke(s.stroke);
        g.draw(s.shape);
        p2d = null;
    }

    @Override
    protected void processEvent(AWTEvent evt) {
        super.processEvent(evt);
        if (evt instanceof MouseEvent) {
            MouseEvent me = (MouseEvent) evt;
            if (me.getID() == MouseEvent.MOUSE_PRESSED) {
                p2d = new Path2D.Float();
                p2d.moveTo(me.getX(), me.getY());
            } else if (p2d != null && me.getID() == MouseEvent.MOUSE_DRAGGED) {
                p2d.lineTo(me.getX(), me.getY());
            } else if (p2d != null && me.getID() == MouseEvent.MOUSE_RELEASED) {
                ShapeDef sd = new ShapeDef(stroke, paint, p2d);
                commitShape(sd);
            }
            repaint();
        }
    }

    public static void main(final String[] args) {
        System.err.println("Annoation tool by simon@dancingcloudservices.com");
        System.err.println("Icons by www.iconfinder.com");
        int x1 = 50, y1 = 50, w1 = 1280, h1 = 720;
        if (args.length == 2 || args.length == 4) {
            w1 = Integer.parseInt(args[0]);
            h1 = Integer.parseInt(args[1]);
            if (args.length == 4) {
                x1 = Integer.parseInt(args[2]);
                y1 = Integer.parseInt(args[3]);
            }
            System.out.println("AnnotationTool " + w1 + " by " + h1 + " offset: " + x1 + "," + y1);
        } else if (args.length != 0) {
            System.err.println("Usage: java annotationtool.AnnotationTool "
                    + "[<width> <height> [ <x> <y>]]"
                    + "\nUsing defaults 1280 720 50 50");
        }
        final int x = x1, y = y1, w = w1, h = h1;
        // Create the GUI on the event-dispatching thread
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                GraphicsEnvironment ge = GraphicsEnvironment
                        .getLocalGraphicsEnvironment();

                // check if the OS supports translucency
                if (ge.getDefaultScreenDevice().isWindowTranslucencySupported(
                        GraphicsDevice.WindowTranslucency.TRANSLUCENT)) {

                    ControllerBox controllerBox = new ControllerBox(
                            new AnnotationTool(x, y, w, h)
                    );
                    controllerBox.setBounds(x + w + 10, y, 0, 0);
                    controllerBox.pack();
                    controllerBox.setVisible(true);
                }
            }
        });
    }
}
