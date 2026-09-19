import { ImageResponse } from "next/og";

export const size = { width: 180, height: 180 };
export const contentType = "image/png";

export default function AppleIcon() {
  return new ImageResponse(
    (
      <div
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          background: "linear-gradient(135deg, #d8b978, #34d399)",
          fontSize: 104,
          fontWeight: 700,
          color: "#04140d",
          fontFamily: "sans-serif",
        }}
      >
        L
      </div>
    ),
    size,
  );
}
