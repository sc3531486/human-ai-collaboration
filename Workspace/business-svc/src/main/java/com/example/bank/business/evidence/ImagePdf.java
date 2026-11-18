package com.example.bank.business.evidence;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
/** 将业务单据影像封装为单页 PDF，保留签名和中文印刷效果。 */
public final class ImagePdf {
    private ImagePdf(){}
    public static byte[] encode(BufferedImage page)throws IOException {
        ByteArrayOutputStream jpeg=new ByteArrayOutputStream();ImageIO.write(page,"jpg",jpeg);
        ByteArrayOutputStream out=new ByteArrayOutputStream();write(out,"%PDF-1.4\n");int[] offsets=new int[6];
        String[] objects={"<< /Type /Catalog /Pages 2 0 R >>","<< /Type /Pages /Kids [3 0 R] /Count 1 >>","<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /XObject << /Sheet 4 0 R >> >> /Contents 5 0 R"+" >>"};
        for(int i=0;i<3;i++){offsets[i+1]=out.size();write(out,(i+1)+" 0 obj\n"+objects[i]+"\nendobj\n");}
        offsets[4]=out.size();write(out,"4 0 obj\n<< /Type /XObject /Subtype /Image /Width "+page.getWidth()+" /Height "+page.getHeight()+" /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length "+jpeg.size()+" >>\nstream\n");jpeg.writeTo(out);write(out,"\nendstream\nendobj\n");
        byte[] content="q 595 0 0 842 0 0 cm /Sheet Do Q".getBytes(StandardCharsets.US_ASCII);
        offsets[5]=out.size();write(out,"5 0 obj\n<< /Length "+content.length+" >>\nstream\n");out.write(content);write(out,"\nendstream\nendobj\n");
        int xref=out.size();write(out,"xref\n0 6\n0000000000 65535 f \n");for(int i=1;i<6;i++)write(out,String.format(java.util.Locale.ROOT,"%010d 00000 n \n",offsets[i]));write(out,"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF\n");return out.toByteArray();
    }
    private static void write(OutputStream out,String value)throws IOException{out.write(value.getBytes(StandardCharsets.US_ASCII));}
}
