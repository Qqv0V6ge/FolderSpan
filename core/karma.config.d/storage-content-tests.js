config.client = config.client || {};
if (process.env.FOLDERSPAN_STORAGE_CONTENT_TEST_URL) {
    config.client.storageContentTestUrl = process.env.FOLDERSPAN_STORAGE_CONTENT_TEST_URL;
}
if (process.env.FOLDERSPAN_STORAGE_CONTENT_RUNTIME_FIXTURE_URL) {
    config.client.storageContentRuntimeFixtureUrl = process.env.FOLDERSPAN_STORAGE_CONTENT_RUNTIME_FIXTURE_URL;
}
