// Run the Karma ChromeHeadless tests with --no-sandbox.
//
// GitHub Actions runners (Ubuntu 23.10+) disable unprivileged user namespaces
// via AppArmor, so Chrome's sandbox can't initialize and ChromeHeadless aborts
// with "No usable sandbox". The test code is trusted, so disabling the sandbox
// is safe here. This applies everywhere (CI + local), which keeps behavior
// consistent.
config.set({
    customLaunchers: {
        ChromeHeadlessNoSandbox: {
            base: "ChromeHeadless",
            flags: ["--no-sandbox", "--disable-gpu"]
        }
    },
    browsers: ["ChromeHeadlessNoSandbox"]
});
