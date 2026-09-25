package app.booxultimatum;

import android.os.Bundle;

// Runs inside Shizuku's shell-uid process. Keep the surface tiny: one command in, code/out/err back.
interface IShellService {
    // Reserved transaction id Shizuku uses to stop user services.
    void destroy() = 16777114;

    Bundle exec(String command) = 1;
}
