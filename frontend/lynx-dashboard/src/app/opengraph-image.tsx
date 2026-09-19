import { ImageResponse } from "next/og";

export const size = { width: 1200, height: 630 };
export const contentType = "image/png";

export default function OpengraphImage() {
  return new ImageResponse(
    (
      <div
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          justifyContent: "center",
          gap: 32,
          background: "#04140d",
          fontFamily: "sans-serif",
        }}
      >
        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            width: 160,
            height: 160,
            borderRadius: 36,
            background: "linear-gradient(135deg, #d8b978, #34d399)",
            fontSize: 96,
            fontWeight: 700,
            color: "#04140d",
          }}
        >
          L
        </div>
        <div style={{ display: "flex", fontSize: 72, fontWeight: 700, color: "#f5f2ea" }}>
          Lynx
        </div>
        <div style={{ display: "flex", fontSize: 28, color: "#9a9488" }}>
          A multi-currency ledger
        </div>
      </div>
    ),
    size,
  );
}
