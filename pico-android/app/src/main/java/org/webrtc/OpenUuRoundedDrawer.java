package org.webrtc;

import android.opengl.GLES20;

/** One-pass premultiplied corner mask, sharing WebRTC's RGB/OES/YUV conversion. */
public final class OpenUuRoundedDrawer implements RendererCommon.GlDrawer {
  static final String FRAGMENT =
      "uniform vec2 u_frame_size;\n"
      + "void main() {\n"
      + "  vec4 color = sample(tc);\n"
      + "  vec2 size = max(u_frame_size, vec2(1.0));\n"
      + "  float radius = clamp(min(size.x, size.y) * 0.018, 2.0, 28.0);\n"
      + "  vec2 edge = min(tc, vec2(1.0) - tc) * size;\n"
      + "  float alpha = 1.0;\n"
      + "  if (edge.x < radius && edge.y < radius) {\n"
      + "    float distance = length(vec2(radius) - edge) - radius;\n"
      + "    alpha = 1.0 - smoothstep(-0.75, 0.75, distance);\n"
      + "  }\n"
      + "  gl_FragColor = color * alpha;\n"
      + "}\n";
  private final GlGenericDrawer drawer = new GlGenericDrawer(FRAGMENT,
      new GlGenericDrawer.ShaderCallbacks() {
        private int sizeUniform;
        @Override public void onNewShader(GlShader shader) {
          sizeUniform = shader.getUniformLocation("u_frame_size");
        }
        @Override public void onPrepareShader(GlShader shader, float[] matrix,
            int width, int height, int viewportWidth, int viewportHeight) {
          GLES20.glUniform2f(sizeUniform, width, height);
        }
      });
  @Override public void drawOes(int texture, float[] matrix, int w, int h, int x, int y, int vw, int vh) {
    drawer.drawOes(texture,matrix,w,h,x,y,vw,vh);
  }
  @Override public void drawRgb(int texture, float[] matrix, int w, int h, int x, int y, int vw, int vh) {
    drawer.drawRgb(texture,matrix,w,h,x,y,vw,vh);
  }
  @Override public void drawYuv(int[] textures, float[] matrix, int w, int h, int x, int y, int vw, int vh) {
    drawer.drawYuv(textures,matrix,w,h,x,y,vw,vh);
  }
  @Override public void release() { drawer.release(); }
}
