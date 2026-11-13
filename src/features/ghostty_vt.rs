//! Optional libghostty-vt terminal backend.
//!
//! Ghostty's VT engine is a system C library (`libghostty-vt`) on Linux. We
//! load it at runtime so builds and platforms without it keep using the
//! in-tree `vt100` parser. Only the VT state is used here; the egui renderer
//! lives in `remote_terminal` and paints a fixed grid either way.
use std::ffi::c_void;
use std::sync::OnceLock;

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct ScreenState {
    pub cols: u16,
    pub rows: u16,
    pub cursor: (u16, u16),
    pub cursor_visible: bool,
    pub cells: Vec<Cell>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct Cell {
    pub text: String,
    pub fg: CellColor,
    pub bg: CellColor,
    pub bold: bool,
    pub dim: bool,
    pub italic: bool,
    pub underline: bool,
    pub inverse: bool,
    pub wide: bool,
    pub continuation: bool,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) enum CellColor {
    #[default]
    None,
    Indexed(u8),
    Rgb(u8, u8, u8),
}

pub(crate) struct Screen {
    terminal: *mut c_void,
    lib: &'static Lib,
}

// The terminal handle is only used while the owning `Mutex<State>` is held.
unsafe impl Send for Screen {}

impl Screen {
    pub(crate) fn new(cols: u16, rows: u16) -> Option<Self> {
        let lib = lib()?;
        let mut terminal = std::ptr::null_mut();
        let result = unsafe {
            (lib.new_terminal)(std::ptr::null(), &mut terminal, cols.max(1), rows.max(1))
        };
        if result != 0 || terminal.is_null() {
            return None;
        }
        Some(Self { terminal, lib })
    }

    pub(crate) fn process(&mut self, bytes: &[u8]) {
        if bytes.is_empty() {
            return;
        }
        unsafe { (self.lib.write)(self.terminal, bytes.as_ptr(), bytes.len()) };
    }

    pub(crate) fn resize(&mut self, cols: u16, rows: u16) {
        unsafe {
            (self.lib.resize)(
                self.terminal,
                cols.max(1),
                rows.max(1),
                8,
                16,
            );
        }
    }

    pub(crate) fn reset(&mut self) {
        unsafe { (self.lib.reset)(self.terminal) };
    }

    pub(crate) fn read_state(&self) -> ScreenState {
        let mut cols = 0u16;
        let mut rows = 0u16;
        let mut cursor_x = 0u16;
        let mut cursor_y = 0u16;
        let mut cursor_visible = false;
        unsafe {
            (self.lib.get)(
                self.terminal,
                DATA_COLS,
                &mut cols as *mut u16 as *mut c_void,
            );
            (self.lib.get)(
                self.terminal,
                DATA_ROWS,
                &mut rows as *mut u16 as *mut c_void,
            );
            (self.lib.get)(
                self.terminal,
                DATA_CURSOR_X,
                &mut cursor_x as *mut u16 as *mut c_void,
            );
            (self.lib.get)(
                self.terminal,
                DATA_CURSOR_Y,
                &mut cursor_y as *mut u16 as *mut c_void,
            );
            (self.lib.get)(
                self.terminal,
                DATA_CURSOR_VISIBLE,
                &mut cursor_visible as *mut bool as *mut c_void,
            );
        }
        let mut state = ScreenState {
            cols,
            rows,
            cursor: (cursor_x, cursor_y),
            cursor_visible,
            cells: vec![Cell::default(); usize::from(cols) * usize::from(rows)],
        };
        for row in 0..rows {
            for col in 0..cols {
                let point = Point {
                    tag: POINT_VIEWPORT,
                    value: PointValue {
                        coordinate: PointCoord {
                            x: col,
                            y: u32::from(row),
                        },
                    },
                };
                let mut reference = GridRef {
                    size: std::mem::size_of::<GridRef>(),
                    node: std::ptr::null_mut(),
                    x: 0,
                    y: 0,
                };
                let result = unsafe {
                    (self.lib.grid_ref)(self.terminal, point, &mut reference)
                };
                if result != 0 {
                    continue;
                }
                let mut raw_cell = 0u64;
                if unsafe { (self.lib.ref_cell)(&reference, &mut raw_cell) } != 0 {
                    continue;
                }
                let mut has_text = false;
                let mut wide = 0i32;
                unsafe {
                    (self.lib.cell_get)(
                        raw_cell,
                        CELL_HAS_TEXT,
                        &mut has_text as *mut bool as *mut c_void,
                    );
                    (self.lib.cell_get)(
                        raw_cell,
                        CELL_WIDE,
                        &mut wide as *mut i32 as *mut c_void,
                    );
                }
                let mut text = String::new();
                if has_text {
                    text = self.graphemes(&reference);
                }
                let mut style = Style::default();
                let style_result =
                    unsafe { (self.lib.ref_style)(&reference, &mut style) };
                let index =
                    usize::from(row) * usize::from(cols) + usize::from(col);
                if style_result != 0 {
                    state.cells[index] = Cell {
                        text,
                        wide: wide == 1,
                        continuation: wide == 2 || wide == 3,
                        ..Default::default()
                    };
                    continue;
                }
                state.cells[index] = Cell {
                    text,
                    fg: color(style.fg),
                    bg: color(style.bg),
                    bold: style.bold,
                    dim: style.faint,
                    italic: style.italic,
                    underline: style.underline != 0,
                    inverse: style.inverse,
                    wide: wide == 1,
                    continuation: wide == 2 || wide == 3,
                };
            }
        }
        state
    }

    fn graphemes(&self, reference: &GridRef) -> String {
        let mut buffer = [0u32; 8];
        let mut length = 0usize;
        let mut result = unsafe {
            (self.lib.ref_graphemes)(
                reference,
                buffer.as_mut_ptr(),
                buffer.len(),
                &mut length,
            )
        };
        if result == RESULT_OUT_OF_SPACE {
            let mut larger = vec![0u32; length.clamp(1, 32)];
            result = unsafe {
                (self.lib.ref_graphemes)(
                    reference,
                    larger.as_mut_ptr(),
                    larger.len(),
                    &mut length,
                )
            };
            if result == 0 {
                return chars(&larger[..length.min(larger.len())]);
            }
            return String::new();
        }
        if result == 0 {
            return chars(&buffer[..length.min(buffer.len())]);
        }
        String::new()
    }
}

impl Drop for Screen {
    fn drop(&mut self) {
        unsafe { (self.lib.free)(self.terminal) };
    }
}

fn chars(codepoints: &[u32]) -> String {
    codepoints
        .iter()
        .filter_map(|codepoint| char::from_u32(*codepoint))
        .collect()
}

fn color(value: StyleColor) -> CellColor {
    match value.tag {
        STYLE_COLOR_PALETTE => CellColor::Indexed(unsafe { value.value.palette }),
        STYLE_COLOR_RGB => {
            let rgb = unsafe { value.value.rgb };
            CellColor::Rgb(rgb.r, rgb.g, rgb.b)
        }
        _ => CellColor::None,
    }
}

fn lib() -> Option<&'static Lib> {
    static LIB: OnceLock<Option<Lib>> = OnceLock::new();
    LIB.get_or_init(|| {
        // Escape hatch for systems with an incompatible libghostty-vt ABI.
        if std::env::var_os("OPENUUYC_GHOSTTY_VT").is_some_and(|value| value == "0") {
            return None;
        }
        unsafe { Lib::load().ok() }
    })
    .as_ref()
}

const POINT_VIEWPORT: i32 = 1;
const DATA_COLS: i32 = 1;
const DATA_ROWS: i32 = 2;
const DATA_CURSOR_X: i32 = 3;
const DATA_CURSOR_Y: i32 = 4;
const DATA_CURSOR_VISIBLE: i32 = 7;
const CELL_WIDE: i32 = 3;
const CELL_HAS_TEXT: i32 = 4;
const RESULT_OUT_OF_SPACE: i32 = -3;
const STYLE_COLOR_PALETTE: i32 = 1;
const STYLE_COLOR_RGB: i32 = 2;

#[repr(C)]
#[derive(Clone, Copy)]
struct PointCoord {
    x: u16,
    y: u32,
}

#[repr(C)]
union PointValue {
    coordinate: PointCoord,
    _padding: [u64; 2],
}

#[repr(C)]
struct Point {
    tag: i32,
    value: PointValue,
}

#[repr(C)]
struct GridRef {
    size: usize,
    node: *mut c_void,
    x: u16,
    y: u16,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct Rgb {
    r: u8,
    g: u8,
    b: u8,
}

#[repr(C)]
#[derive(Clone, Copy)]
union StyleColorValue {
    palette: u8,
    rgb: Rgb,
    _padding: u64,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct StyleColor {
    tag: i32,
    value: StyleColorValue,
}

#[repr(C)]
struct Style {
    size: usize,
    fg: StyleColor,
    bg: StyleColor,
    underline_color: StyleColor,
    bold: bool,
    italic: bool,
    faint: bool,
    blink: bool,
    inverse: bool,
    invisible: bool,
    strikethrough: bool,
    overline: bool,
    underline: i32,
}

impl Default for Style {
    fn default() -> Self {
        let empty = StyleColor {
            tag: 0,
            value: StyleColorValue { _padding: 0 },
        };
        Self {
            size: std::mem::size_of::<Self>(),
            fg: empty,
            bg: empty,
            underline_color: empty,
            bold: false,
            italic: false,
            faint: false,
            blink: false,
            inverse: false,
            invisible: false,
            strikethrough: false,
            overline: false,
            underline: 0,
        }
    }
}

struct Lib {
    _library: libloading::Library,
    new_terminal:
        unsafe extern "C" fn(*const c_void, *mut *mut c_void, u16, u16) -> i32,
    free: unsafe extern "C" fn(*mut c_void),
    resize: unsafe extern "C" fn(*mut c_void, u16, u16, u32, u32) -> i32,
    write: unsafe extern "C" fn(*mut c_void, *const u8, usize),
    reset: unsafe extern "C" fn(*mut c_void),
    get: unsafe extern "C" fn(*mut c_void, i32, *mut c_void) -> i32,
    grid_ref: unsafe extern "C" fn(*mut c_void, Point, *mut GridRef) -> i32,
    ref_cell: unsafe extern "C" fn(*const GridRef, *mut u64) -> i32,
    ref_graphemes:
        unsafe extern "C" fn(*const GridRef, *mut u32, usize, *mut usize) -> i32,
    ref_style: unsafe extern "C" fn(*const GridRef, *mut Style) -> i32,
    cell_get: unsafe extern "C" fn(u64, i32, *mut c_void) -> i32,
}

// Function pointers and the loaded library are immutable after OnceLock init.
unsafe impl Sync for Lib {}

impl Lib {
    unsafe fn load() -> Result<Self, libloading::Error> {
        let library = unsafe { libloading::Library::new("libghostty-vt.so.0") }
            .or_else(|_| unsafe { libloading::Library::new("libghostty-vt.so") })?;
        unsafe {
            Ok(Self {
                new_terminal: *library.get(b"ghostty_terminal_new\0")?,
                free: *library.get(b"ghostty_terminal_free\0")?,
                resize: *library.get(b"ghostty_terminal_resize\0")?,
                write: *library.get(b"ghostty_terminal_vt_write\0")?,
                reset: *library.get(b"ghostty_terminal_reset\0")?,
                get: *library.get(b"ghostty_terminal_get\0")?,
                grid_ref: *library.get(b"ghostty_terminal_grid_ref\0")?,
                ref_cell: *library.get(b"ghostty_grid_ref_cell\0")?,
                ref_graphemes: *library.get(b"ghostty_grid_ref_graphemes\0")?,
                ref_style: *library.get(b"ghostty_grid_ref_style\0")?,
                cell_get: *library.get(b"ghostty_cell_get\0")?,
                _library: library,
            })
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ghostty_parses_text_colors_and_cursor_when_available() {
        let Some(mut screen) = Screen::new(20, 4) else {
            // The system library is optional; the vt100 fallback is tested
            // through `remote_terminal`.
            return;
        };
        screen.process(b"hi\x1b[31m red\x1b[0m");
        let state = screen.read_state();
        assert_eq!(state.cols, 20);
        assert_eq!(state.rows, 4);
        assert_eq!(state.cursor, (6, 0));
        assert!(state.cursor_visible);
        assert_eq!(state.cells[0].text, "h");
        assert_eq!(state.cells[1].text, "i");
        assert_eq!(state.cells[3].text, "r");
        assert_eq!(state.cells[3].fg, CellColor::Indexed(1));
        assert_eq!(state.cells[0].fg, CellColor::None);
    }

    #[test]
    fn ghostty_resize_and_reset_are_safe_when_available() {
        let Some(mut screen) = Screen::new(20, 4) else {
            return;
        };
        screen.process(b"old");
        screen.resize(10, 3);
        let state = screen.read_state();
        assert_eq!(state.cols, 10);
        assert_eq!(state.rows, 3);
        screen.reset();
        let state = screen.read_state();
        assert!(state.cells.iter().all(|cell| cell.text.is_empty()));
    }
}
