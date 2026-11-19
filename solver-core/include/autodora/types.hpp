#pragma once

#include <array>
#include <cstdint>
#include <string_view>

namespace autodora {

constexpr uint8_t kMinBoardDim = 3;
constexpr uint8_t kMaxBoardDim = 8;
constexpr uint8_t kMaxCells = kMaxBoardDim * kMaxBoardDim; // 64 cells -> fits in uint64_t bitmask
constexpr uint8_t kNumElements = 6;

enum class Element : uint8_t {
    Water = 0,
    Fire = 1,
    Wood = 2,
    Light = 3,
    Dark = 4,
    Heart = 5,
    Hidden = 6,
    Unknown = 7,
    Empty = 8
};

constexpr bool isMatchableElement(Element e) noexcept {
    return static_cast<uint8_t>(e) < kNumElements;
}

constexpr uint8_t elementBit(Element e) noexcept {
    return isMatchableElement(e) ? static_cast<uint8_t>(1u << static_cast<uint8_t>(e)) : 0u;
}

constexpr char elementToChar(Element e) noexcept {
    switch (e) {
        case Element::Water: return 'W';
        case Element::Fire:  return 'F';
        case Element::Wood:  return 'G';
        case Element::Light: return 'L';
        case Element::Dark:  return 'D';
        case Element::Heart: return 'H';
        case Element::Hidden: return '?';
        case Element::Unknown: return 'U';
        case Element::Empty: return '.';
    }
    return 'U';
}

constexpr Element charToElement(char ch) noexcept {
    switch (ch) {
        case 'W': case 'w': case 'b': case 'B': return Element::Water;
        case 'F': case 'f': case 'r': case 'R': return Element::Fire;
        case 'G': case 'g': case 'E': case 'e': return Element::Wood;
        case 'L': case 'l': case 'y': case 'Y': return Element::Light;
        case 'D': case 'd': case 'p': case 'P': return Element::Dark;
        case 'H': case 'h': case 'c': case 'C': return Element::Heart;
        case '?': return Element::Hidden;
        case '.': case '_': return Element::Empty;
        default:  return Element::Unknown;
    }
}

enum class Variant : uint8_t {
    Normal = 0,
    Enchanted = 1, // 强化符石
    Race = 2,      // 种族符石
    Weakened = 3   // 弱化符石
};

// Hazards attached to the orb itself (move when the orb is swapped)
namespace HazardBits {
constexpr uint16_t None        = 0;
constexpr uint16_t Weathered   = 1u << 0;  // 风化珠 (触碰断珠/扣血; 可选撞掉解除)
constexpr uint16_t Electrified = 1u << 1;  // 电击珠 (禁碰 + 未消必败/高优首消)
constexpr uint16_t BurningOrb  = 1u << 2;  // 附着燃烧
constexpr uint16_t StickyOrb   = 1u << 3;  // 附着黏腐
constexpr uint16_t Frozen      = 1u << 4;  // 冻结 (未消下回合变冰封, 高优消除)
constexpr uint16_t IceSealed   = 1u << 5;  // 冰封 (不可移动 + 不可消除)
constexpr uint16_t Locked      = 1u << 6;  // 锁定/凝结 (不可移动)
constexpr uint16_t Petrified   = 1u << 7;  // 石化 (不可移动 + 不可直消, 消四邻解层)
constexpr uint16_t BloodDrain  = 1u << 8;  // 化血 (未消帮敌回血, 高优首消)
constexpr uint16_t Bomb        = 1u << 9;  // 炸弹 (未消爆炸, 高优首消)
constexpr uint16_t BlackWhite  = 1u << 10; // 黑白珠
constexpr uint16_t Question    = 1u << 11; // 问号珠
constexpr uint16_t Priority    = 1u << 12; // 单格优先必消 (继承旧版 OrbSetting)
constexpr uint16_t Reserve     = 1u << 13; // 单格保留不消 (继承旧版 OrbSetting)
} // namespace HazardBits

// Fixed terrain / shield markers attached to grid coordinate (r, c) (do NOT move with orbs)
namespace TileFlagBits {
constexpr uint16_t None         = 0;
constexpr uint16_t Puzzle1      = 1u << 0;  // 拼图盾区域 1
constexpr uint16_t Puzzle2      = 1u << 1;  // 拼图盾区域 2 (双拼图支持)
constexpr uint16_t StartAllowed = 1u << 2;  // 红绿灯: 允许起手格 (绿灯)
constexpr uint16_t EndRequired  = 1u << 3;  // 红绿灯: 要求停手格 (红灯)
constexpr uint16_t Checkpoint   = 1u << 4;  // 途经地形格
constexpr uint16_t Storm        = 1u << 5;  // 暴风区域 (禁碰 + 不可消除)
constexpr uint16_t Shattered    = 1u << 6;  // 碎裂格 (允许经过, 但该格不可消除!)
constexpr uint16_t BurningTile  = 1u << 7;  // 灼热/燃烧地形 (经过扣血)
constexpr uint16_t StickyTile   = 1u << 8;  // 黏腐地形 (触碰即停手)
constexpr uint16_t BlackHole    = 1u << 9;  // 黑洞 (禁经过)
constexpr uint16_t NoPass       = 1u << 10; // 绝对禁行
constexpr uint16_t VoidTile     = 1u << 11; // 缩盘/无效格
} // namespace TileFlagBits

enum class Direction : uint8_t {
    Up = 0,
    Down = 1,
    Left = 2,
    Right = 3,
    UpLeft = 4,
    UpRight = 5,
    DownLeft = 6,
    DownRight = 7,
    None = 255
};

constexpr std::array<int8_t, 8> kDirDr = {-1, 1, 0, 0, -1, -1, 1, 1};
constexpr std::array<int8_t, 8> kDirDc = {0, 0, -1, 1, -1, 1, -1, 1};

constexpr bool isDiagonal(Direction d) noexcept {
    return static_cast<uint8_t>(d) >= 4 && static_cast<uint8_t>(d) < 8;
}

constexpr Direction oppositeDirection(Direction d) noexcept {
    switch (d) {
        case Direction::Up:        return Direction::Down;
        case Direction::Down:      return Direction::Up;
        case Direction::Left:      return Direction::Right;
        case Direction::Right:     return Direction::Left;
        case Direction::UpLeft:    return Direction::DownRight;
        case Direction::DownRight: return Direction::UpLeft;
        case Direction::UpRight:   return Direction::DownLeft;
        case Direction::DownLeft:  return Direction::UpRight;
        default:                   return Direction::None;
    }
}

struct CellCoord {
    uint8_t row = 0;
    uint8_t col = 0;

    constexpr bool operator==(const CellCoord& other) const noexcept {
        return row == other.row && col == other.col;
    }
};

constexpr uint8_t cellIndex(uint8_t row, uint8_t col, uint8_t cols) noexcept {
    return static_cast<uint8_t>(row * cols + col);
}

constexpr uint64_t bitOf(uint8_t idx) noexcept {
    return uint64_t{1} << idx;
}

} // namespace autodora
