// Copyright 2014-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

#pragma once

#include <array>
#include <memory>
#include <string>
#include "common/common_types.h"
#include "common/settings.h"

class INIReader;

class Config {
private:
    std::unique_ptr<INIReader> android_config;
    std::string android_config_loc;

    bool LoadINI(const std::string& default_contents = "", bool retry = true);
    void ReadValues();

public:
    /// One screen of the custom layout: x, y, width and height.
    using ScreenRect = std::array<u16, 4>;

    /**
     * Screen rectangles of the running game.
     *
     * config.ini is read again while a game runs (every Config::Reload(), which reloadSettings()
     * on the frontend triggers), and that would reset the custom layout to the global values, so
     * the per game rectangles are kept here and applied again after every read. The first index is
     * the orientation (0 landscape, 1 portrait), the second one the screen (0 top, 1 bottom).
     */
    struct GameScreenRects {
        bool set[2][2]{};
        ScreenRect rect[2][2]{};
    };

    Config();
    ~Config();

    void Reload();

    /// Keeps one screen of the custom layout for the running game and applies it to the settings.
    static void SetGameScreenRect(bool portrait, bool top, const ScreenRect& rect);
    /// Drops the per game rectangles; the global config.ini ones are used again on the next read.
    static void ClearGameScreenRects();

private:
    static void ApplyGameScreenRects();

    static GameScreenRects game_screen_rects;

    /**
     * Applies a value read from the android_config to a Setting.
     *
     * @param group The name of the INI group
     * @param setting The yuzu setting to modify
     */
    template <typename Type, bool ranged>
    void ReadSetting(const std::string& group, Settings::Setting<Type, ranged>& setting);
};
