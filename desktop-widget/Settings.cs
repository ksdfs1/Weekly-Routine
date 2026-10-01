using System.Text.Json;

namespace WeeklyRoutineWidget;

/// Window position/size, kept in %APPDATA%\WeeklyRoutineWidget\settings.json.
sealed class Settings
{
    public const int DefaultWidth = 640, DefaultHeight = 300;

    public int X { get; set; } = int.MinValue;   // MinValue = never placed yet
    public int Y { get; set; } = int.MinValue;
    public int Width { get; set; } = DefaultWidth;
    public int Height { get; set; } = DefaultHeight;
    /// false until the first run has turned "start with Windows" on (after that the tray menu decides)
    public bool AutostartSetUp { get; set; }

    public static string Folder =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "WeeklyRoutineWidget");
    static string FilePath => Path.Combine(Folder, "settings.json");

    public static Settings Load()
    {
        try
        {
            if (File.Exists(FilePath))
            {
                var s = JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new Settings();
                // still an old default (960x250, or 1280x340 from the 7-day layout): take the current one,
                // re-placed in the corner
                if ((s.Width == 960 && s.Height == 250) || (s.Width == 1280 && s.Height == 340))
                {
                    s.Width = DefaultWidth; s.Height = DefaultHeight;
                    s.X = s.Y = int.MinValue;
                }
                return s;
            }
        }
        catch { /* unreadable settings: fall back to defaults */ }
        return new Settings();
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(Folder);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { /* not worth bothering the user about */ }
    }
}
