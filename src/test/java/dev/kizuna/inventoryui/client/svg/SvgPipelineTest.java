package dev.kizuna.inventoryui.client.svg;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

class SvgPipelineTest {
    private static SvgMesh mesh(String content) throws Exception {
        var input = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        return new SvgTessellator().tessellate(new RestrictedSvgParser().parse(input));
    }

    private static String svg(String shapes) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\">"
                + shapes
                + "</svg>";
    }

    @Test
    void concavePolygonPreservesAreaAndSvgRgbaColor() throws Exception {
        // 凹多边形不能用三角扇错误填满缺口，颜色转换必须保留 SVG 的尾部 alpha。
        var mesh =
                mesh(svg("<polygon points=\"0,0 10,0 10,4 4,4 4,10 0,10\" fill=\"#12345680\"/>"));
        double area = 0;
        for (var triangle : mesh.triangles()) {
            var a = triangle.a();
            var b = triangle.b();
            var c = triangle.c();
            area +=
                    Math.abs((b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x()))
                            / 2;
            assertEquals(0x80123456, a.color());
        }
        assertEquals(64, area, "凹形缺口必须保持透明");
    }

    @Test
    void externalEntitiesUnsupportedFeaturesAndInvalidGeometryAreRejected() {
        // 资源包也不能借 XML 打开外部文件，不能把未支持功能静默画成错误结果。
        String[] invalid = {
            "<!DOCTYPE svg [<!ENTITY ext SYSTEM 'file:///not-readable'>]>" + svg("&ext;"),
            svg("<image href=\"https://invalid.example/image\"/>"),
            svg("<rect x=\"0\" y=\"0\" width=\"11\" height=\"1\" opacity=\"0\"/>"),
            svg("<polygon points=\"0,0 10,10 10,0 0,10\"/>"),
            svg("<circle cx=\"5\" cy=\"5\" r=\"NaN\"/>")
        };
        for (String input : invalid) {
            assertThrows(Exception.class, () -> mesh(input));
        }
    }
}
