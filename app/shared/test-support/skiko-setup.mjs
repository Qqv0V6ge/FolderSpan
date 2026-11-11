import { api } from "folderspan-skiko-test-symbols";

before(function () {
    this.timeout(30000);
    return api.awaitSkiko;
});
