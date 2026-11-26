# Git Setup

This folder is prepared as the public source tree for PZ Reverse Mapper.

## First commit

```powershell
cd path\to\PZReverseMapper-public
git init
git add .
git commit -m "Initial public release"
git branch -M main
git tag v0.1.0
```

## Push to GitHub

```powershell
git remote add origin https://github.com/Unjammer/PZReverseMapper.git
git push -u origin main
git push origin v0.1.0
```

## Build check

```bash
mvn -B verify
```

The runnable jar is written to `target/PZReverseMapper-<version>.jar`.

## Release

Pushing a `v*` tag runs `.github/workflows/release.yml`, which builds and tests
the tagged commit and publishes `PZReverseMapper-<version>.jar` and its
`.sha256` checksum as a GitHub release:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The same workflow can be started from the Actions tab with a version number; it
then creates the tag on the selected branch.

The repository intentionally ignores generated exports, game files, build
outputs, IDE state, and local validation folders.
