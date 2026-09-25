import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

public class MakeIcon {
    // 路径从命令行参数或当前工作目录推导，避免写死某台机器的绝对路径。
    //   java tools/MakeIcon.java <源图.jpg> [资源目录]
    static final String SRC = System.getProperty("icon.src",
        System.getenv().getOrDefault("ICON_SRC", "icon-source.jpg"));
    static final String RES = System.getProperty("icon.res",
        System.getenv().getOrDefault("ICON_RES", "app/src/main/res"));
    static final String[] DENS = {"mdpi","hdpi","xhdpi","xxhdpi","xxxhdpi"};
    static final double[] FACTOR = {1, 1.5, 2, 3, 4};

    static String hex(int p){ return String.format("#%06X", p & 0xFFFFFF); }

    static void hints(Graphics2D g){
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    public static void main(String[] args) throws Exception {
        String srcPath = args.length > 0 ? args[0] : SRC;
        String resPath = args.length > 1 ? args[1] : RES;

        File srcFile = new File(srcPath);
        if (!srcFile.isFile()) {
            System.err.println("找不到源图：" + srcFile.getAbsolutePath());
            System.err.println("用法：java tools/MakeIcon.java <源图.jpg> [资源目录]");
            System.exit(1);
        }
        File resDir = new File(resPath);
        if (!resDir.isDirectory()) {
            System.err.println("找不到资源目录：" + resDir.getAbsolutePath());
            System.err.println("请在项目根目录下运行，或显式传入资源目录。");
            System.exit(1);
        }

        BufferedImage src = ImageIO.read(srcFile);
        if (src == null) {
            System.err.println("无法解析图片（需为 PNG/JPEG）：" + srcFile.getAbsolutePath());
            System.exit(1);
        }
        int w = src.getWidth(), h = src.getHeight();
        System.out.println("源图: " + srcFile.getPath() + " " + w + "x" + h);
        System.out.println("输出: " + resDir.getPath());
        System.out.println("四角: " + hex(src.getRGB(0,0)) + " " + hex(src.getRGB(w-1,0))
            + " " + hex(src.getRGB(0,h-1)) + " " + hex(src.getRGB(w-1,h-1)));

        // 白底检测：源图四角应当接近纯白，否则「白底转透明」的假设不成立
        int c0 = src.getRGB(0,0);
        if (Math.min((c0>>16)&255, Math.min((c0>>8)&255, c0&255)) < 200) {
            System.err.println("警告：源图左上角不是白色，白底转透明的效果可能不符合预期。");
        }

        // 1) 取最深的像素作为墨色（线条本色）
        int inkMin = 255, ir=0, ig=0, ib=0;
        for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
            int p = src.getRGB(x,y);
            int r=(p>>16)&255, g=(p>>8)&255, b=p&255;
            int mn = Math.min(r, Math.min(g,b));
            if (mn < inkMin) { inkMin=mn; ir=r; ig=g; ib=b; }
        }
        System.out.printf("识别到的墨色: #%02X%02X%02X (最暗通道=%d)%n", ir,ig,ib,inkMin);

        // 2) 白底转透明：alpha = 到白色的距离 / 墨色到白色的距离
        //    这样抗锯齿边缘会变成半透明，换任何背景都不露白边
        double inkDist = 255.0 - inkMin;
        BufferedImage art = new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
        int minX=w, minY=h, maxX=-1, maxY=-1;
        for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
            int p = src.getRGB(x,y);
            int r=(p>>16)&255, g=(p>>8)&255, b=p&255;
            int mn = Math.min(r, Math.min(g,b));
            double a = (255.0 - mn) / inkDist;
            if (a<0) a=0; if (a>1) a=1;
            int A = (int)Math.round(a*255);
            art.setRGB(x,y,(A<<24)|(ir<<16)|(ig<<8)|ib);
            if (A > 8) { if(x<minX)minX=x; if(y<minY)minY=y; if(x>maxX)maxX=x; if(y>maxY)maxY=y; }
        }
        int cw = maxX-minX+1, ch = maxY-minY+1;
        System.out.println("内容裁剪框: " + cw + "x" + ch + " @ (" + minX + "," + minY + ")");
        BufferedImage content = new BufferedImage(cw,ch,BufferedImage.TYPE_INT_ARGB);
        Graphics2D cg = content.createGraphics(); hints(cg);
        cg.drawImage(art, 0,0,cw,ch, minX,minY,minX+cw,minY+ch, null); cg.dispose();

        // 3) 各密度输出
        for (int i=0;i<DENS.length;i++){
            String d = DENS[i];
            int legacy = (int)Math.round(48*FACTOR[i]);       // 传统图标 48dp
            int fg     = (int)Math.round(108*FACTOR[i]);      // 自适应前景 108dp
            File dir = new File(resDir, "mipmap-"+d); dir.mkdirs();

            // 自适应前景：画布 108dp，内容限制在中心 66dp 安全区
            BufferedImage f = new BufferedImage(fg,fg,BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = f.createGraphics(); hints(g);
            // 系统会把 108dp 画布按 108/72 放大后裁切，因此图案要相对
            // 中心 72dp 可见区来定尺寸：留出安全余量，取 72dp 的 86% ≈ 62dp
            double box = fg*(72.0/108.0)*0.86;
            double sc = Math.min(box/content.getWidth(), box/content.getHeight());
            int tw=(int)Math.round(content.getWidth()*sc), th=(int)Math.round(content.getHeight()*sc);
            g.drawImage(content,(fg-tw)/2,(fg-th)/2,tw,th,null); g.dispose();
            // 背景层不在这里生成：它是纯色，用矢量更合适，
            // 见 app/src/main/res/drawable/ic_launcher_background.xml
            ImageIO.write(f,"png",new File(dir,"ic_launcher_foreground.png"));

            // 传统方形：白色圆角底 + 图案
            ImageIO.write(plate(content,legacy,false),"png",new File(dir,"ic_launcher.png"));
            // 传统圆形
            ImageIO.write(plate(content,legacy,true ),"png",new File(dir,"ic_launcher_round.png"));
            System.out.println("  mipmap-"+d+": legacy="+legacy+"px  foreground="+fg+"px");
        }
        System.out.println("完成");
    }

    /** 白底（圆角方形或圆形）+ 居中图案 */
    static BufferedImage plate(BufferedImage art, int size, boolean round) {
        BufferedImage out = new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics(); hints(g);
        g.setColor(Color.WHITE);
        if (round) g.fill(new Ellipse2D.Double(0,0,size,size));
        else g.fill(new RoundRectangle2D.Double(0,0,size,size,size*0.22,size*0.22));
        double box = size*0.70;
        double sc = Math.min(box/art.getWidth(), box/art.getHeight());
        int tw=(int)Math.round(art.getWidth()*sc), th=(int)Math.round(art.getHeight()*sc);
        g.drawImage(art,(size-tw)/2,(size-th)/2,tw,th,null);
        g.dispose();
        return out;
    }
}
