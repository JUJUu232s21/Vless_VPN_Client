import android.app.Application;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.SetupOptions;
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            SetupOptions options = new SetupOptions();
            options.setBasePath(getFilesDir().getAbsolutePath());
            options.setWorkingPath(getFilesDir().getAbsolutePath());
            options.setTempPath(getCacheDir().getAbsolutePath());
            options.setLogMaxLines(1000);
            options.setDebug(true);
            Libbox.setup(options);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
