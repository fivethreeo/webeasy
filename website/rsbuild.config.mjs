import { defineConfig } from "@rsbuild/core";
import { fileURLToPath } from "node:url";
import { generate } from "./scripts/build.mjs";
import { localConnectionMiddleware } from "./scripts/local-connection.mjs";
import { devHostMiddleware } from "./scripts/dev-host.mjs";

const source = fileURLToPath(new URL("./src/", import.meta.url));

export default defineConfig(({ env }) => {
  const development = env === "development";
  return {
    plugins: [{
      name: "webeasy-catalog",
      setup(api) {
        api.onAfterBuild(() => generate(false));
        api.onBeforeStartDevServer(() => generate(true));
        api.onAfterDevCompile(() => generate(true));
      },
    }],
    source: {
      entry: {
        site: "./src/site.js",
        editor: "./src/editor.js",
        field: "./src/field.js",
        generator: "./src/generator.js",
        ...(development ? { provision: "./src/provision.js" } : {}),
      },
    },
    output: {
      assetPrefix: "./",
      filenameHash: false,
      distPath: { root: development ? ".preview" : "dist", js: "assets", css: "assets" },
      filename: { js: "[name].js", css: "[name].css" },
      injectStyles: false,
      sourceMap: { js: false, css: false },
    },
    splitChunks: false,
    tools: {
      htmlPlugin: false,
      rspack: {
        optimization: { runtimeChunk: false },
        plugins: development ? [{
          apply(compiler) {
            compiler.hooks.afterCompile.tap("webeasy-catalog-dependencies", (compilation) => {
              compilation.fileDependencies.add(`${source}catalog.json`);
              compilation.fileDependencies.add(`${source}content.json`);
              compilation.fileDependencies.add(`${source}editor.html`);
              compilation.fileDependencies.add(`${source}field.html`);
              compilation.fileDependencies.add(`${source}generator.html`);
              compilation.fileDependencies.add(fileURLToPath(new URL("../shared/storage.sql", import.meta.url)));
              compilation.fileDependencies.add(`${source}provision.html`);
              for (const directory of ["templates", "components", "assets"]) {
                compilation.contextDependencies.add(`${source}${directory}`);
              }
            });
          },
        }] : [],
      },
    },
    server: {
      host: process.env.WEBEASY_DEV_HOST || "127.0.0.1",
      port: Number(process.env.RSBUILD_PORT || 4173),
      strictPort: true,
      open: false,
      cors: false,
      publicDir: { name: development ? ".preview" : "dist", copyOnBuild: false },
      headers: { "Cache-Control": "no-store" },
      setup({ server, action }) {
        if (development && action === "dev") {
          server.middlewares.use(localConnectionMiddleware(
            fileURLToPath(new URL("./hosting.local.json", import.meta.url)),
          ));
          server.middlewares.use(devHostMiddleware(
            fileURLToPath(new URL("../dev-host/public/", import.meta.url)),
          ));
        }
      },
    },
  };
});
