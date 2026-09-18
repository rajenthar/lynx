import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // This app lives inside the larger lynx repo (a separate git root one
  // level up) — pins Turbopack's root here instead of it walking up and
  // finding that unrelated lockfile.
  turbopack: {
    root: __dirname,
  },
};

export default nextConfig;
