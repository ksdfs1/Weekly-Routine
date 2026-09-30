using System.Text.Json;

namespace WeeklyRoutineWidget;

/// Window position/size, kept in %APPDATA%\WeeklyRoutineWidget\settings.json.
sealed class Settings
{
    public int X { get; set; } = int.MinValue;   // MinValue = never placed yet
    public int Y { get; set; } = int.MinValue;
    public int Width { get; set; } = 960;
    public int Height { get; set; } = 250;

    public static string Folder =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "WeeklyRoutineWidget");
    static string FilePath => Path.Combine(Folder, "settings.json");

    public static Settings Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new Settings();
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
