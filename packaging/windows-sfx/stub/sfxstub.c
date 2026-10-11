/* sfxstub.c -- extract an appended 7z archive and start the installer.
The 7z decoder is the public-domain LZMA SDK. This file is not an SFX module
from 7-Zip or from Oleg Scherbakov, and it does not delete the extracted files.

Extract path: %LOCALAPPDATA%\RobotMC Installer
Launch name: RobotMC Installer.exe
*/

#include "Precomp.h"

#include "7z.h"
#include "7zAlloc.h"
#include "7zCrc.h"

#include <windows.h>
#include <stdio.h>

#define SFX_INSTALL_DIR_NAME L"RobotMC Installer"
#define SFX_LAUNCHER_NAME L"RobotMC Installer.exe"
#define SFX_LOOK_BUF_SIZE ((size_t)1 << 18)
#define SFX_PATH_CAP 32768

static const ISzAlloc g_Alloc = { SzAlloc, SzFree };

typedef struct
{
  ISeekInStream vt;
  HANDLE file;
  UInt64 base;
  UInt64 pos;
  UInt64 size;
} COverlayStream;

static void Report(const wchar_t *message)
{
  wchar_t logPath[SFX_PATH_CAP];
  DWORD logLen = GetEnvironmentVariableW(L"ROBOTMC_SFX_LOG", logPath, SFX_PATH_CAP);
  if (logLen > 0 && logLen < SFX_PATH_CAP)
  {
    HANDLE log = CreateFileW(logPath, FILE_APPEND_DATA, FILE_SHARE_READ, NULL,
        OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (log != INVALID_HANDLE_VALUE)
    {
      DWORD written = 0;
      char utf8[2048];
      int bytes = WideCharToMultiByte(CP_UTF8, 0, message, -1, utf8, (int)sizeof utf8, NULL, NULL);
      if (bytes > 1)
        WriteFile(log, utf8, (DWORD)(bytes - 1), &written, NULL);
      WriteFile(log, "\r\n", 2, &written, NULL);
      CloseHandle(log);
    }
  }
  if (AttachConsole(ATTACH_PARENT_PROCESS))
  {
    HANDLE out = CreateFileW(L"CONOUT$", GENERIC_WRITE, FILE_SHARE_WRITE, NULL, OPEN_EXISTING, 0, NULL);
    if (out != INVALID_HANDLE_VALUE)
    {
      DWORD written = 0;
      WriteConsoleW(out, message, lstrlenW(message), &written, NULL);
      WriteConsoleW(out, L"\r\n", 2, &written, NULL);
      CloseHandle(out);
    }
    FreeConsole();
  }
}

static int ReadAt(HANDLE file, UInt64 offset, void *buf, DWORD size)
{
  LARGE_INTEGER pos;
  DWORD read = 0;
  pos.QuadPart = (LONGLONG)offset;
  if (!SetFilePointerEx(file, pos, NULL, FILE_BEGIN))
    return 0;
  if (!ReadFile(file, buf, size, &read, NULL) || read != size)
    return 0;
  return 1;
}

static UInt32 ReadUi32(const Byte *p)
{
  return (UInt32)p[0]
      | ((UInt32)p[1] << 8)
      | ((UInt32)p[2] << 16)
      | ((UInt32)p[3] << 24);
}

static UInt64 ReadUi64(const Byte *p)
{
  return (UInt64)ReadUi32(p) | ((UInt64)ReadUi32(p + 4) << 32);
}

static UInt64 PeOverlayOffset(HANDLE file, UInt64 fileSize)
{
  Byte dos[64];
  Byte nt[sizeof(IMAGE_NT_HEADERS64)];
  IMAGE_NT_HEADERS64 headers;
  UInt64 sectionOffset;
  WORD index;
  DWORD overlay = 0;
  LONG lfanew;

  if (fileSize < sizeof(dos) || !ReadAt(file, 0, dos, sizeof dos))
    return 0;
  if (dos[0] != 'M' || dos[1] != 'Z')
    return 0;
  lfanew = (LONG)ReadUi32(dos + 0x3C);
  if (lfanew < 0 || (UInt64)lfanew + sizeof(headers) > fileSize)
    return 0;
  if (!ReadAt(file, (UInt64)lfanew, nt, sizeof nt))
    return 0;
  memcpy(&headers, nt, sizeof headers);
  if (headers.Signature != IMAGE_NT_SIGNATURE)
    return 0;
  if (headers.OptionalHeader.Magic != IMAGE_NT_OPTIONAL_HDR64_MAGIC)
    return 0;
  sectionOffset = (UInt64)lfanew + 4 + sizeof(IMAGE_FILE_HEADER) + headers.FileHeader.SizeOfOptionalHeader;
  for (index = 0; index < headers.FileHeader.NumberOfSections; index++)
  {
    IMAGE_SECTION_HEADER section;
    DWORD end;
    if (!ReadAt(file, sectionOffset + (UInt64)index * sizeof(section), &section, sizeof section))
      return 0;
    end = section.PointerToRawData + section.SizeOfRawData;
    if (end > overlay)
      overlay = end;
  }
  return overlay;
}

static int ArchiveHeaderAt(HANDLE file, UInt64 fileSize, UInt64 offset)
{
  Byte header[k7zStartHeaderSize];
  UInt64 nextOffset;
  UInt64 nextSize;
  if (offset + k7zStartHeaderSize > fileSize)
    return 0;
  if (!ReadAt(file, offset, header, k7zStartHeaderSize))
    return 0;
  if (header[0] != '7' || header[1] != 'z' || header[2] != 0xBC || header[3] != 0xAF
      || header[4] != 0x27 || header[5] != 0x1C)
    return 0;
  if (header[6] != 0)
    return 0;
  if (CrcCalc(header + 12, 20) != ReadUi32(header + 8))
    return 0;
  nextOffset = ReadUi64(header + 12);
  nextSize = ReadUi64(header + 20);
  if (nextSize == 0 || nextOffset >= ((UInt64)1 << 62) || nextSize >= ((UInt64)1 << 48))
    return 0;
  return offset + k7zStartHeaderSize + nextOffset + nextSize == fileSize;
}

static int FindArchiveOffset(HANDLE file, UInt64 fileSize, UInt64 *archiveOffset)
{
  UInt64 overlay = PeOverlayOffset(file, fileSize);
  UInt64 scanEnd;
  UInt64 offset;
  if (overlay == 0 || overlay >= fileSize)
    return 0;
  scanEnd = overlay + (1u << 20);
  if (scanEnd > fileSize)
    scanEnd = fileSize;
  for (offset = overlay; offset + k7zStartHeaderSize <= scanEnd; offset++)
  {
    if (ArchiveHeaderAt(file, fileSize, offset))
    {
      *archiveOffset = offset;
      return 1;
    }
  }
  return 0;
}

static SRes OverlayRead(ISeekInStreamPtr pp, void *buf, size_t *size)
{
  Z7_CONTAINER_FROM_VTBL_TO_DECL_VAR_pp_vt_p(COverlayStream)
  UInt64 remain;
  size_t toRead;
  LARGE_INTEGER pos;
  DWORD read = 0;

  if (p->pos >= p->size)
  {
    *size = 0;
    return SZ_OK;
  }
  remain = p->size - p->pos;
  toRead = *size;
  if ((UInt64)toRead > remain)
    toRead = (size_t)remain;
  if (toRead > (size_t)(1u << 30))
    toRead = (size_t)(1u << 30);
  pos.QuadPart = (LONGLONG)(p->base + p->pos);
  if (!SetFilePointerEx(p->file, pos, NULL, FILE_BEGIN))
    return SZ_ERROR_READ;
  if (!ReadFile(p->file, buf, (DWORD)toRead, &read, NULL))
    return SZ_ERROR_READ;
  p->pos += read;
  *size = read;
  return SZ_OK;
}

static SRes OverlaySeek(ISeekInStreamPtr pp, Int64 *pos, ESzSeek origin)
{
  Z7_CONTAINER_FROM_VTBL_TO_DECL_VAR_pp_vt_p(COverlayStream)
  Int64 next;
  if (origin == SZ_SEEK_SET)
    next = *pos;
  else if (origin == SZ_SEEK_CUR)
    next = (Int64)p->pos + *pos;
  else if (origin == SZ_SEEK_END)
    next = (Int64)p->size + *pos;
  else
    return SZ_ERROR_FAIL;
  if (next < 0)
    return SZ_ERROR_FAIL;
  p->pos = (UInt64)next;
  *pos = next;
  return SZ_OK;
}

static int EnsureDirectory(const wchar_t *path)
{
  DWORD attr = GetFileAttributesW(path);
  if (attr != INVALID_FILE_ATTRIBUTES)
    return (attr & FILE_ATTRIBUTE_DIRECTORY) != 0;
  if (CreateDirectoryW(path, NULL))
    return 1;
  return GetLastError() == ERROR_ALREADY_EXISTS;
}

static int BuildInstallDir(wchar_t *dest, size_t cap)
{
  wchar_t local[SFX_PATH_CAP];
  DWORD n = GetEnvironmentVariableW(L"LOCALAPPDATA", local, SFX_PATH_CAP);
  int written;
  if (n == 0 || n >= SFX_PATH_CAP)
    return 0;
  while (n > 0 && (local[n - 1] == L'\\' || local[n - 1] == L'/'))
    local[--n] = 0;
  if (n < 3)
    return 0;
  written = swprintf(dest, cap, L"%ls\\%ls", local, SFX_INSTALL_DIR_NAME);
  if (written <= 0 || (size_t)written >= cap)
    return 0;
  /* Win32 file APIs reject a normal path at MAX_PATH. The \\?\ prefix lifts that
     limit. CreateProcess still receives this install directory. */
  if ((size_t)written >= MAX_PATH
      && !(local[0] == L'\\' && local[1] == L'\\' && local[2] == L'?' && local[3] == L'\\'))
  {
    written = swprintf(dest, cap, L"\\\\?\\%ls\\%ls", local, SFX_INSTALL_DIR_NAME);
  }
  return written > 0 && (size_t)written < cap;
}

static int AcceptSegment(const UInt16 *start, size_t length)
{
  size_t i;
  if (length == 0)
    return 0;
  if (length == 1 && start[0] == '.')
    return 0;
  if (length == 2 && start[0] == '.' && start[1] == '.')
    return 0;
  for (i = 0; i < length; i++)
  {
    unsigned c = start[i];
    if (c < 32 || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|')
      return 0;
  }
  return 1;
}

static int JoinRelative(wchar_t *dest, size_t cap, const wchar_t *installDir, const UInt16 *name, int isDir)
{
  size_t len = wcslen(installDir);
  const UInt16 *cursor = name;
  if (len + 1 >= cap)
    return 0;
  memcpy(dest, installDir, (len + 1) * sizeof(wchar_t));
  if (cursor[0] == 0)
    return isDir;
  if (cursor[0] == '/' || cursor[0] == '\\')
    return 0;
  while (cursor[0] != 0)
  {
    const UInt16 *start = cursor;
    size_t segment;
    size_t i;
    while (cursor[0] != 0 && cursor[0] != '/' && cursor[0] != '\\')
      cursor++;
    segment = (size_t)(cursor - start);
    if (!AcceptSegment(start, segment))
      return 0;
    if (len + 1 + segment >= cap)
      return 0;
    dest[len++] = L'\\';
    for (i = 0; i < segment; i++)
      dest[len++] = (wchar_t)start[i];
    dest[len] = 0;
    if (cursor[0] == '/' || cursor[0] == '\\')
    {
      if (!EnsureDirectory(dest))
        return 0;
      cursor++;
      if (cursor[0] == 0)
        return 1;
    }
  }
  if (isDir && !EnsureDirectory(dest))
    return 0;
  return 1;
}

static int WriteExtractedFile(const wchar_t *path, const Byte *data, size_t size)
{
  HANDLE out;
  DWORD attr = GetFileAttributesW(path);
  if (attr != INVALID_FILE_ATTRIBUTES && (attr & FILE_ATTRIBUTE_DIRECTORY))
    return 0;
  if (attr != INVALID_FILE_ATTRIBUTES && (attr & FILE_ATTRIBUTE_READONLY))
    SetFileAttributesW(path, attr & ~FILE_ATTRIBUTE_READONLY);
  out = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
  if (out == INVALID_HANDLE_VALUE)
    return 0;
  while (size > 0)
  {
    DWORD chunk = size > (1u << 30) ? (1u << 30) : (DWORD)size;
    DWORD written = 0;
    if (!WriteFile(out, data, chunk, &written, NULL) || written != chunk)
    {
      CloseHandle(out);
      return 0;
    }
    data += chunk;
    size -= chunk;
  }
  CloseHandle(out);
  return 1;
}

static int ExtractAll(CSzArEx *db, ILookInStreamPtr stream, const wchar_t *installDir)
{
  UInt32 index;
  UInt32 blockIndex = 0xFFFFFFFF;
  Byte *outBuffer = NULL;
  size_t outBufferSize = 0;
  int ok = 0;

  if (!EnsureDirectory(installDir))
  {
    wchar_t message[1024];
    swprintf(message, 1024, L"cannot create the install directory (%lu): %ls", GetLastError(), installDir);
    Report(message);
    return 0;
  }
  for (index = 0; index < db->NumFiles; index++)
  {
    size_t nameLen = SzArEx_GetFileNameUtf16(db, index, NULL);
    UInt16 *name;
    wchar_t path[SFX_PATH_CAP];
    int isDir = SzArEx_IsDir(db, index);
    size_t offset = 0;
    size_t outSize = 0;
    SRes res;
    if (nameLen == 0 || nameLen > (1u << 20))
      goto done;
    name = (UInt16 *)SzAlloc(NULL, nameLen * sizeof(UInt16));
    if (!name)
      goto done;
    SzArEx_GetFileNameUtf16(db, index, name);
    if (!JoinRelative(path, SFX_PATH_CAP, installDir, name, isDir))
    {
      SzFree(NULL, name);
      Report(L"archive path escapes the install directory");
      goto done;
    }
    SzFree(NULL, name);
    if (isDir)
      continue;
    res = SzArEx_Extract(db, stream, index, &blockIndex, &outBuffer, &outBufferSize,
        &offset, &outSize, &g_Alloc, &g_Alloc);
    if (res != SZ_OK)
    {
      wchar_t message[128];
      swprintf(message, 128, L"cannot decode archive entry (code %d)", (int)res);
      Report(message);
      goto done;
    }
    if (!WriteExtractedFile(path, outBuffer + offset, outSize))
    {
      Report(L"cannot write an extracted file");
      goto done;
    }
  }
  ok = 1;
done:
  ISzAlloc_Free(&g_Alloc, outBuffer);
  return ok;
}

static int LaunchInstaller(const wchar_t *installDir, DWORD *exitCode)
{
  wchar_t launcher[SFX_PATH_CAP];
  wchar_t command[SFX_PATH_CAP];
  STARTUPINFOW startup;
  PROCESS_INFORMATION process;
  DWORD code = 1;
  int written = swprintf(launcher, SFX_PATH_CAP, L"%ls\\%ls", installDir, SFX_LAUNCHER_NAME);
  if (written <= 0 || (size_t)written >= SFX_PATH_CAP)
    return 0;
  if (GetFileAttributesW(launcher) == INVALID_FILE_ATTRIBUTES)
  {
    Report(L"RobotMC Installer.exe was not extracted");
    return 0;
  }
  written = swprintf(command, SFX_PATH_CAP, L"\"%ls\"", launcher);
  if (written <= 0)
    return 0;
  memset(&startup, 0, sizeof startup);
  startup.cb = sizeof startup;
  memset(&process, 0, sizeof process);
  if (!CreateProcessW(launcher, command, NULL, NULL, FALSE, 0, NULL, installDir, &startup, &process))
  {
    Report(L"cannot start RobotMC Installer.exe");
    return 0;
  }
  WaitForSingleObject(process.hProcess, INFINITE);
  GetExitCodeProcess(process.hProcess, &code);
  CloseHandle(process.hThread);
  CloseHandle(process.hProcess);
  *exitCode = code;
  return 1;
}

static int SfxMain(void)
{
  wchar_t modulePath[SFX_PATH_CAP];
  wchar_t installDir[SFX_PATH_CAP];
  HANDLE file = INVALID_HANDLE_VALUE;
  LARGE_INTEGER fileSize;
  UInt64 archiveOffset = 0;
  COverlayStream overlay;
  CLookToRead2 look;
  CSzArEx db;
  DWORD exitCode = 1;
  int code = 1;
  SRes res;
  DWORD pathLen = GetModuleFileNameW(NULL, modulePath, SFX_PATH_CAP);

  memset(&overlay, 0, sizeof overlay);
  memset(&look, 0, sizeof look);
  SzArEx_Init(&db);
  if (pathLen == 0 || pathLen >= SFX_PATH_CAP)
  {
    Report(L"cannot locate this executable");
    goto done;
  }
  file = CreateFileW(modulePath, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
  if (file == INVALID_HANDLE_VALUE)
  {
    Report(L"cannot open this executable");
    goto done;
  }
  if (!GetFileSizeEx(file, &fileSize) || fileSize.QuadPart <= 0)
  {
    Report(L"cannot read this executable");
    goto done;
  }
  CrcGenerateTable();
  if (!FindArchiveOffset(file, (UInt64)fileSize.QuadPart, &archiveOffset))
  {
    Report(L"appended 7z archive not found");
    goto done;
  }
  overlay.vt.Read = OverlayRead;
  overlay.vt.Seek = OverlaySeek;
  overlay.file = file;
  overlay.base = archiveOffset;
  overlay.pos = 0;
  overlay.size = (UInt64)fileSize.QuadPart - archiveOffset;
  LookToRead2_CreateVTable(&look, False);
  look.buf = (Byte *)ISzAlloc_Alloc(&g_Alloc, SFX_LOOK_BUF_SIZE);
  look.bufSize = SFX_LOOK_BUF_SIZE;
  look.realStream = &overlay.vt;
  LookToRead2_INIT(&look)
  if (!look.buf)
  {
    Report(L"out of memory");
    goto done;
  }
  res = SzArEx_Open(&db, &look.vt, &g_Alloc, &g_Alloc);
  if (res != SZ_OK)
  {
    wchar_t message[128];
    swprintf(message, 128, L"cannot open 7z archive (code %d)", (int)res);
    Report(message);
    goto done;
  }
  if (!BuildInstallDir(installDir, SFX_PATH_CAP))
  {
    Report(L"LOCALAPPDATA is not set");
    goto done;
  }
  if (!ExtractAll(&db, &look.vt, installDir))
    goto done;
  if (!LaunchInstaller(installDir, &exitCode))
    goto done;
  code = (int)exitCode;
done:
  SzArEx_Free(&db, &g_Alloc);
  ISzAlloc_Free(&g_Alloc, look.buf);
  if (file != INVALID_HANDLE_VALUE)
    CloseHandle(file);
  return code;
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR command, int show)
{
  (void)instance;
  (void)previous;
  (void)command;
  (void)show;
  return SfxMain();
}
