package com.orangeom.poissonimage;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;
import java.io.*;
import java.util.ArrayList;
import java.util.Stack;

/**
 * Created by matthew on 6/28/2017.
 */
public class PoissonImage
{
    public static void main(String[] args)
    {
        SwingUtilities.invokeLater(() -> initGUI());
    }

    private static void initGUI()
    {
        JFrame f = new JFrame("PoissonImage");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        PoissonImagePanel panel = new PoissonImagePanel();
        f.add(panel);
        f.setJMenuBar(panel.getMenuBar());
        f.pack();
        f.setVisible(true);
    }
}

enum PoissonState
{
    DRAGGING, BORDER
}

class PoissonImagePanel extends JPanel implements ActionListener, ItemListener
{
    private static final int MASK_INSIDE = -1;
    private static final int MASK_BORDER = -2;
    private static final int MASK_OUTSIDE = -3;
    private int m_cursorX = 0;
    private int m_cursorY = 0;

    private int m_imageX = 0;
    private int m_imageY = 0;
    private int m_imageW = 0;
    private int m_imageH = 0;
    private int m_maskW = 0;
    private int m_maskH = 0;

    private ArrayList<Point2> m_borderPoints;
    private ArrayList<Point2> m_cutPoints;
    private int[][] m_mask;

    private BufferedImage m_targetImage;
    private BufferedImage m_targetBackup;
    private BufferedImage m_sourceImage;
    private BufferedImage m_cutImage;

    private PoissonState m_state;
    private boolean m_showCutImage = true;
    private boolean m_useMixedGradients = true;

    private JFileChooser m_fileChooser;

    private boolean pointInImage(int x, int y)
    {
        return !(x < m_imageX || x > m_imageX + m_imageW || y < m_imageY || y > m_imageY + m_imageH);
    }

    private Point2 clipPointToImage(int x, int y)
    {
        int px = x;
        int py = y;

        px = Math.max(m_imageX, px);
        px = Math.min(m_imageX + m_imageW, px);
        py = Math.max(m_imageY, py);
        py = Math.min(m_imageY + m_imageH, py);

        return new Point2(px, py);
    }

    private Point2 clipPointToBox(int x0, int y0, int w, int h, int x, int y)
    {
        int px = x;
        int py = y;

        px = Math.max(x0, px);
        px = Math.min(x0 + w, px);
        py = Math.max(y0, py);
        py = Math.min(y0 + h, py);

        return new Point2(px, py);
    }

    private void moveBorder(int dx, int dy)
    {
        for (Point2 p : m_borderPoints)
        {
            p.add(dx, dy);
        }
    }

    private void clearMask()
    {
        for (int x = 0; x < m_maskW; x++)
        {
            for (int y = 0; y < m_maskH; y++)
            {
                m_mask[x][y] = MASK_INSIDE;
            }
        }
    }

    private void getCutAreaPoints()
    {
        // Fill in pixels of border
        int len = m_borderPoints.size();
        for (int i = 0; i < len; i++)
        {
            Point2 start = m_borderPoints.get(i);
            start = clipPointToBox(0, 0, m_maskW - 1, m_maskH - 1, start.x, start.y);
            Point2 end = m_borderPoints.get((i + 1) % len);
            end = clipPointToBox(0, 0, m_maskW - 1, m_maskH - 1, end.x, end.y);
            Point2 d = end.sub(start);

            double r = 1.0 / (double) Math.abs(d.x);
            int dir = d.x > 0 ? 1 : -1;
            for (int x = 0; x < Math.abs(d.x); x++)
            {

                int h = (int) Math.round(r * (double)x * (double) d.y);
                m_mask[start.x + x * dir][start.y + h] = MASK_BORDER;
            }

            r = 1.0 / (double) Math.abs(d.y);
            dir = d.y > 0 ? 1 : -1;

            for (int y = 0; y < Math.abs(d.y); y++)
            {
                int w = (int) Math.round(r * (double)y * (double) d.x);
                m_mask[start.x + w][start.y + y * dir] = MASK_BORDER;
            }
        }

        // Find an outside point
        Stack<Point2> stack = new Stack<>();
        outer:
        for (int y = 0; y < m_maskH; y++)
        {
            for (int x = 0; x < m_maskW; x++)
            {
                if (m_mask[x][y] == MASK_INSIDE)
                {
                    stack.push(new Point2(x, y));
                    break outer;
                }
            }
        }

        // Fill all outside points
        while (!stack.empty())
        {
            Point2 p = stack.pop();
            if (p.x < 0 || p.x > m_maskW - 1 || p.y < 0 || p.y > m_maskH - 1)
            {
                continue;
            }
            if (m_mask[p.x][p.y] == MASK_OUTSIDE)
            {
                continue;
            }
            if (m_mask[p.x][p.y] == MASK_BORDER)
            {
                continue;
            }
            m_mask[p.x][p.y] = MASK_OUTSIDE;
            stack.push(new Point2(p.x + 1, p.y));
            stack.push(new Point2(p.x - 1, p.y));
            stack.push(new Point2(p.x, p.y + 1));
            stack.push(new Point2(p.x, p.y - 1));
        }

        m_cutPoints.clear();
        for (int y = 0; y < m_maskH; y++)
        {
            for (int x = 0; x < m_maskW; x++)
            {
                if (m_mask[x][y] == MASK_INSIDE)
                {
                    m_cutPoints.add(new Point2(x - m_imageX, y - m_imageY));
                    m_mask[x][y] = m_cutPoints.size() - 1;
                }
            }
        }
    }

    private void getCutImage()
    {
        m_cutImage = new BufferedImage(m_imageW, m_imageH, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < m_imageH; y++)
        {
            for (int x = 0; x < m_imageW; x++)
            {
                int rx = m_imageX + x;
                int ry = m_imageY + y;

                if (!(rx < 0 || rx >= m_maskW || ry < 0 || ry >= m_maskH)
                        && m_mask[rx][ry] > MASK_INSIDE)
                {
                    int c = m_sourceImage.getRGB(x, y);
                    Color newC = new Color((c & 0x00ff0000) >> 16, (c & 0x0000ff00) >> 8, c & 0x000000ff, 255);
                    m_cutImage.setRGB(x, y, newC.getRGB());

                }
                else
                {
                    Color newC = new Color(255, 255, 255, 0);
                    m_cutImage.setRGB(x, y, newC.getRGB());
                }
            }
        }
    }

    // https://stackoverflow.com/a/3514297
    static BufferedImage copyImage(BufferedImage b)
    {
        ColorModel c = b.getColorModel();
        boolean isAlphaPremul = c.isAlphaPremultiplied();
        WritableRaster raster = b.copyData(null);
        return new BufferedImage(c, raster, isAlphaPremul, null);
    }

    public PoissonImagePanel()
    {
        InputStream targetStream = PoissonImage.class.getResourceAsStream("/Tropical-Island-2.jpg");
        InputStream sourceStream = PoissonImage.class.getResourceAsStream("/rainbow.jpg");

        try
        {
            m_targetImage = ImageIO.read(targetStream);
            m_sourceImage = ImageIO.read(sourceStream);
            m_imageW = m_sourceImage.getWidth();
            m_imageH = m_sourceImage.getHeight();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }

        m_targetBackup = copyImage(m_targetImage);

        m_borderPoints = new ArrayList<>();
        m_cutPoints = new ArrayList<>();
        m_maskW = m_targetImage.getWidth();
        m_maskH = m_targetImage.getHeight();
        m_mask = new int[m_maskW][m_maskH];
        clearMask();

        setBorder(BorderFactory.createLineBorder(Color.black));
        setFocusable(true);
        requestFocusInWindow();

        m_fileChooser = new JFileChooser();
        m_fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

        m_state = PoissonState.DRAGGING;

        addMouseMotionListener(new MouseAdapter()
        {
            @Override
            public void mouseMoved(MouseEvent e)
            {
                m_cursorX = e.getX();
                m_cursorY = e.getY();
            }

            public void mouseDragged(MouseEvent e)
            {
                int x = e.getX();
                int y = e.getY();

                if (m_state == PoissonState.DRAGGING)
                {
                    if (pointInImage(x, y))
                    {
                        int dx = m_cursorX - x;
                        int dy = m_cursorY - y;

                        if (m_imageX - dx > -m_imageW && m_imageX - dx < m_mask.length
                                && m_imageY - dy > -m_imageH && m_imageY - dy < m_mask[0].length)
                        {
                            m_imageX -= dx;
                            m_imageY -= dy;
                            moveBorder(-dx, -dy);
                        }
                    }

                    m_cursorX = x;
                    m_cursorY = y;
                }
                else if (m_state == PoissonState.BORDER)
                {
                    m_borderPoints.add(clipPointToImage(x, y));
                }

                repaint();
            }
        });

        addKeyListener(new KeyAdapter()
        {
            @Override
            public void keyPressed(KeyEvent e)
            {
                if (e.getKeyCode() == KeyEvent.VK_CONTROL)
                {
                    m_state = PoissonState.BORDER;
                }
                repaint();
            }

            @Override
            public void keyReleased(KeyEvent e)
            {
                if (e.getKeyCode() == KeyEvent.VK_CONTROL)
                {
                    m_state = PoissonState.DRAGGING;
                }
                repaint();
            }
        });
    }

    public JMenuBar getMenuBar()
    {
        JMenuBar menuBar;
        JMenu menu;
        JMenuItem menuItem;
        JCheckBoxMenuItem cbMenuItem;

        menuBar = new JMenuBar();
        menu = new JMenu("File");
        menuBar.add(menu);

        menuItem = new JMenuItem("Select target image");
        menuItem.addActionListener(this);
        menu.add(menuItem);

        menuItem = new JMenuItem("Select source image");
        menuItem.addActionListener(this);
        menu.add(menuItem);

        menuItem = new JMenuItem("Save", KeyEvent.VK_S);
        menuItem.addActionListener(this);
        menuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, ActionEvent.CTRL_MASK));
        menu.add(menuItem);

        menu = new JMenu("Edit");
        menuBar.add(menu);

        cbMenuItem = new JCheckBoxMenuItem("Show image", true);
        cbMenuItem.addItemListener(this);
        cbMenuItem.setMnemonic(KeyEvent.VK_I);
        cbMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, ActionEvent.CTRL_MASK));
        menu.add(cbMenuItem);

        cbMenuItem = new JCheckBoxMenuItem("Use mixed gradients", true);
        cbMenuItem.addItemListener(this);
        menu.add(cbMenuItem);

        menu.addSeparator();

        menuItem = new JMenuItem("Reset image position");
        menuItem.addActionListener(this);
        menu.add(menuItem);

        menuItem = new JMenuItem("Reset target image");
        menuItem.addActionListener(this);
        menu.add(menuItem);

        menuItem = new JMenuItem("Clear image", KeyEvent.VK_R);
        menuItem.addActionListener(this);
        menuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, ActionEvent.CTRL_MASK));
        menu.add(menuItem);

        menu.addSeparator();

        menuItem = new JMenuItem("Cut image", KeyEvent.VK_E);
        menuItem.addActionListener(this);
        menuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, ActionEvent.CTRL_MASK));
        menu.add(menuItem);

        menuItem = new JMenuItem("Blend", KeyEvent.VK_B);
        menuItem.addActionListener(this);
        menuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_B, ActionEvent.CTRL_MASK));
        menu.add(menuItem);

        return menuBar;
    }

    public void actionPerformed(ActionEvent e)
    {
        JMenuItem source = (JMenuItem)(e.getSource());
        if ("Select target image".equals(source.getText()))
        {
            int ret = m_fileChooser.showOpenDialog(PoissonImagePanel.this);

            if (ret == JFileChooser.APPROVE_OPTION)
            {
                File file = m_fileChooser.getSelectedFile();
                try
                {
                    m_targetImage = ImageIO.read(file);
                    m_maskW = m_targetImage.getWidth();
                    m_maskH = m_targetImage.getHeight();
                    m_mask = new int[m_maskW][m_maskH];
                    clearMask();
                }
                catch (IOException ex)
                {
                    ex.printStackTrace();
                }
                m_targetBackup = copyImage(m_targetImage);
            }
        }
        if ("Select source image".equals(source.getText()))
        {
            int ret = m_fileChooser.showOpenDialog(PoissonImagePanel.this);

            if (ret == JFileChooser.APPROVE_OPTION)
            {
                File file = m_fileChooser.getSelectedFile();
                try
                {
                    m_sourceImage = ImageIO.read(file);
                    m_imageW = m_sourceImage.getWidth();
                    m_imageH = m_sourceImage.getHeight();
                    m_imageX = 0;
                    m_imageY = 0;
                    m_borderPoints = new ArrayList<>();
                    m_cutPoints = new ArrayList<>();
                    m_cutImage = null;
                    clearMask();
                }
                catch (IOException ex)
                {
                    ex.printStackTrace();
                }
            }
        }
        if ("Save".equals(source.getText()))
        {
            int ret = m_fileChooser.showSaveDialog(PoissonImagePanel.this);

            if (ret == JFileChooser.APPROVE_OPTION)
            {
                File file = m_fileChooser.getSelectedFile();
                if (!file.toString().endsWith(".png"))
                {
                    file = new File(file + ".png");
                }
                try
                {
                    ImageIO.write(m_targetImage, "png", file);
                }
                catch (IOException ex)
                {
                    ex.printStackTrace();
                }
            }
        }
        if ("Reset image position".equals(source.getText()))
        {
            m_imageX = 0;
            m_imageY = 0;
        }
        if ("Clear image".equals(source.getText()))
        {
            m_cutImage = null;
            clearMask();
            m_borderPoints.clear();
            m_cutPoints.clear();
        }
        if ("Reset target image".equals(source.getText()))
        {
            m_targetImage = copyImage(m_targetBackup);
        }
        if ("Cut image".equals(source.getText()))
        {
            clearMask();
            getCutAreaPoints();
            getCutImage();
        }
        if ("Blend".equals(source.getText()))
        {
            clearMask();
            getCutAreaPoints();
            getCutImage();
            Solver solver = new Solver(m_targetImage, m_cutImage, m_cutPoints,
                    m_mask, m_imageX, m_imageY, m_useMixedGradients);
            solver.run();
            solver.updateTarget();
        }
        repaint();
    }

    public void itemStateChanged(ItemEvent e)
    {
        JCheckBoxMenuItem source = (JCheckBoxMenuItem)(e.getSource());
        if ("Show image".equals(source.getText()))
        {
            m_showCutImage = !m_showCutImage;
        }
        if ("Use mixed gradients".equals(source.getText()))
        {
            m_useMixedGradients = !m_useMixedGradients;
        }
        repaint();
    }

    public Dimension getPreferredSize()
    {
        return new Dimension(3840, 2160);
    }

    protected void paintImageBorder(Graphics g)
    {
        g.setColor(Color.black);
        int len = m_borderPoints.size();
        for (int i = 0; i < len; i++)
        {
            Point2 a = m_borderPoints.get(i);
            Point2 b = m_borderPoints.get((i + 1) % len);
            g.drawLine(a.x, a.y, b.x, b.y);
        }
    }

    protected void paintArea(Graphics g)
    {
        g.setColor(new Color(50, 205, 50, 122));
        for (Point2 p : m_cutPoints)
        {
            g.drawLine(p.x + m_imageX, p.y + m_imageY, p.x + m_imageX, p.y + m_imageY);
        }
    }

    protected void displayMask(Graphics g)
    {
        BufferedImage image = new BufferedImage(m_maskW, m_maskH, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < m_maskW; x++)
        {
            for (int y = 0; y < m_maskH; y++)
            {
                if (m_mask[x][y] == MASK_BORDER)
                {
                    image.setRGB(x, y, Color.BLUE.getRGB());
                }
                else if (m_mask[x][y] > MASK_INSIDE)
                {
                    image.setRGB(x, y, Color.WHITE.getRGB());
                }
                else
                {
                    image.setRGB(x, y, Color.BLACK.getRGB());
                }
            }
        }
        g.drawImage(image, m_maskW, 0, this);
    }

    protected void paintComponent(Graphics g)
    {
        super.paintComponent(g);
        g.drawImage(m_targetImage, 0, 0, this);
//        displayMask(g);

        if (m_showCutImage)
        {
            if (m_cutImage != null)
            {
                g.drawImage(m_cutImage, m_imageX, m_imageY, this);
            }
            else
            {
                g.drawImage(m_sourceImage, m_imageX, m_imageY, this);
                paintImageBorder(g);
            }
        }

//        paintArea(g);
    }
}
