## Context

Copy and move tasks already persist execution manifests split into directory and file queues. Directory entries are created before file copies, which already covers empty directories because traversal emits directory entries even when they contain no files. 0-byte files, however, currently share the same file-copy queue as non-empty files.

## Goals / Non-Goals

- Goals: create all directories first, create 0-byte files second, copy non-empty files last, and preserve item-level retry/continue semantics.
- Non-goals: add user preferences, change non-empty transfer protocols, change large-file checkpoints, or alter delete ordering.

## Decisions

- Add an `EMPTY_FILES` queue category for copy-stage manifests and process it between `DIRECTORIES` and `FILES`.
- Add an `EMPTY_FILE_CREATE` entry kind so task text and retry fallbacks can say "创建空文件" instead of "复制文件" or "创建目录".
- Use existing target create-file APIs for empty files: local `FileUtils.createFile`, device `createFiles`, and network `createFile`.
- Keep empty directories in the existing directory queue; no separate empty-directory category is needed.

## Risks / Trade-offs

- Persisted queue directory names gain a new `empty-files` bucket. Existing tasks without that bucket still load through the existing directory/file buckets.
- Some remote listings may report unknown file sizes as `0`. This change follows current behavior, which already treats `!isDirectory && size == 0L` as a create-file case in several copy paths.

## Migration Plan

No data migration is required. Existing unfinished tasks continue using their persisted `directories` and `files` queues. New manifests write `empty-files` when 0-byte file entries are present.
