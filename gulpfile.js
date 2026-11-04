const gulp = require("gulp");
const webpack = require("webpack-stream");
const replace = require("gulp-replace");
const clean = require("gulp-clean");
const argv = require("yargs").argv;
const fs = require("fs");
const path = require("path");

function dropCache() {
  return gulp
    .src(["./src/main/resources/public/dist"], {
      read: false,
      allowEmpty: true,
    })
    .pipe(clean());
}

// Appel direct du webpack embarqué par webpack-stream (webpack 1, celui qui a toujours produit
// les bundles) : ses flux « through » ne se raccordent pas à ceux de gulp 5. Les erreurs de
// typage restent signalées sans interrompre le build, comme avant.
function buildDev(done) {
  const config = require("./webpack.config.js");
  config.output.path = path.resolve(__dirname, "./src/main/resources/public/dist");
  webpack.webpack(config, (err, stats) => {
    if (stats) console.log(stats.toString({ colors: false, chunks: false }));
    done(err);
  });
}

// Pas de behaviours.js dans ce module : seules les vues sont copiées.
function build() {
  return gulp
    .src("./src/main/resources/view-src/**/*.+(html|json|csv)")
    .pipe(replace("@@VERSION", Date.now()))
    .pipe(gulp.dest("./src/main/resources/view"));
}

gulp.task("drop-cache", dropCache);
gulp.task("build-dev", buildDev);
gulp.task("build", build);

function getModName(fileContent) {
  const getProp = function (prop) {
    return fileContent.split(prop + "=")[1].split(/\r?\n/)[0];
  };
  return (
    getProp("modowner") + "~" + getProp("modname") + "~" + getProp("version")
  );
}

function watchFiles() {
  let springboard = argv.springboard;
  if (!springboard) {
    springboard = "../springboard-open-ent/";
  }
  if (springboard[springboard.length - 1] !== "/") {
    springboard += "/";
  }

  gulp.watch(
    "./src/main/resources/public/ts/**/*.ts",
    gulp.series("drop-cache", "build-dev", "build")
  );

  fs.readFile("./gradle.properties", "utf8", function (err, content) {
    const modName = getModName(content);
    gulp.watch(["./src/main/resources/public/js"], () => {
      console.log("Copying resources to " + springboard + "mods/" + modName);
      return gulp
        .src("./src/main/resources/**/*", { encoding: false })
        .pipe(gulp.dest(springboard + "mods/" + modName));
    });
    gulp.watch(
      [
        "./src/main/resources/public/template/**/*.html",
        "!./src/main/resources/public/template/entcore/*.html",
      ],
      () => {
        console.log("Copying resources to " + springboard + "mods/" + modName);
        return gulp
          .src("./src/main/resources/**/*", { encoding: false })
          .pipe(gulp.dest(springboard + "mods/" + modName));
      }
    );

    gulp.watch("./src/main/resources/view/**/*.html", () => {
      console.log("Copying resources to " + springboard + "mods/" + modName);
      return gulp
        .src("./src/main/resources/**/*", { encoding: false })
        .pipe(gulp.dest(springboard + "mods/" + modName));
    });
  });
}

gulp.task("watch", watchFiles);

exports.watch = gulp.parallel("watch");
exports.build = gulp.series("drop-cache", "build-dev", "build");
