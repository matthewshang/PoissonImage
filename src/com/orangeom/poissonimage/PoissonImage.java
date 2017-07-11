package com.orangeom.poissonimage;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Stack;

/**
 * Created by matthew on 6/28/2017.
 */
public class PoissonImage
{
    public static void main(String[] args)
    {
        SwingUtilities.invokeLater(new Runnable() {
            public void run()
            {
                initGUI();
            }
        });
    }

    private static void initGUI()
    {
        JFrame f = new JFrame("PoissonImage");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.add(new PoissonImagePanel());
        f.pack();
        f.setVisible(true);
    }
}

enum PoissonState
{
    DRAGGING, BORDER
}

class PoissonImagePanel extends JPanel
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

    private BufferedImage m_targetImage = null;
    private BufferedImage m_sourceImage = null;
    private BufferedImage m_cutImage = null;

    private PoissonState m_state = PoissonState.DRAGGING;

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

    public PoissonImagePanel()
    {
//        InputStream targetStream = PoissonImage.class.getResourceAsStream("/red.png");
//        InputStream sourceStream = PoissonImage.class.getResourceAsStream("/gray.png");
        InputStream sourceStream = PoissonImage.class.getResourceAsStream("/words.jpg");
//        InputStream sourceStream = PoissonImage.class.getResourceAsStream("/obama.jpg");



//        InputStream targetStream = PoissonImage.class.getResourceAsStream("/sky.jpg");
        InputStream targetStream = PoissonImage.class.getResourceAsStream("/brick.jpg");
//        InputStream targetStream = PoissonImage.class.getResourceAsStream("/photo.jpg");


//        InputStream sourceStream = PoissonImage.class.getResourceAsStream("/jet.jpg");
//        InputStream sourceStream = PoissonImage.class.getResourceAsStream("/balloon.jpg");


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

        m_borderPoints = new ArrayList<>();
        m_cutPoints = new ArrayList<>();
        m_maskW = m_targetImage.getWidth();
        m_maskH = m_targetImage.getHeight();
        m_mask = new int[m_maskW][m_maskH];
        clearMask();

        setBorder(BorderFactory.createLineBorder(Color.black));
        setFocusable(true);
        requestFocusInWindow();

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
                System.out.println(e.getKeyChar());
                if (e.getKeyCode() == KeyEvent.VK_B)
                {
                    m_state = PoissonState.BORDER;
                }
                if (e.getKeyCode() == KeyEvent.VK_D)
                {
                    m_state = PoissonState.DRAGGING;
                }
                if (e.getKeyCode() == KeyEvent.VK_C)
                {
                    m_cutImage = null;
                    clearMask();
                    m_borderPoints.clear();
                    m_cutPoints.clear();
                }
                if (e.getKeyCode() == KeyEvent.VK_F)
                {
                    clearMask();
                    getCutAreaPoints();
                    getCutImage();
                }
                if (e.getKeyCode() == KeyEvent.VK_S)
                {
                    clearMask();
                    getCutAreaPoints();
                    getCutImage();
                    Solver solver = new Solver(m_targetImage, m_cutImage, m_cutPoints, m_mask, m_imageX, m_imageY);
                    solver.run();
                    solver.updateTarget();
                }

                repaint();
            }
        });
    }

    public Dimension getPreferredSize()
    {
        return new Dimension(1920, 1080);
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
        displayMask(g);

        if (m_cutImage != null)
        {
            g.drawImage(m_cutImage, m_imageX, m_imageY, this);
        }
        else
        {
            g.drawImage(m_sourceImage, m_imageX, m_imageY, this);
            paintImageBorder(g);
        }

//        paintArea(g);
    }
}
