package com.orangeom.poissonimage;

/**
 * Created by matthew on 6/29/2017.
 */
public class Point2
{
    public int x;
    public int y;

    public Point2()
    {
        x = 0;
        y = 0;
    }

    public Point2(int _x, int _y)
    {
        x = _x;
        y = _y;
    }

    public void add(int dx, int dy)
    {
        x += dx;
        y += dy;
    }

    public Point2 add(Point2 p)
    {
        return new Point2(x + p.x, y + p.y);
    }

    public Point2 sub(Point2 p)
    {
        return new Point2(x - p.x, y - p.y);
    }
}
