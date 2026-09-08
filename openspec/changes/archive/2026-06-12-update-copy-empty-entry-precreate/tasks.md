## 1. Implementation

- [x] 1.1 Add tests for copy manifest classification of directories, empty directories, 0-byte files, and non-empty files.
- [x] 1.2 Add tests for copy-stage execution order: directories before empty files before non-empty files.
- [x] 1.3 Add the empty-file queue category and empty-file entry kind to runtime persistence and display text.
- [x] 1.4 Split copy manifest file entries into empty-file creation entries and non-empty file copy entries.
- [x] 1.5 Execute empty-file queues with adaptive batch processing and target create-file APIs.
- [x] 1.6 Ensure small-file archive selection excludes empty-file queue entries.
- [x] 1.7 Run OpenSpec validation and targeted/shared JVM tests.
