// Device-local, bounded in-memory streams. This does not grant filesystem access.
function installPythonIo(library) {
  // Skulpt exposes the legacy IOError name but omits Python 3's OSError.
  if (!Sk.builtins.OSError) {
    Sk.builtins.OSError = Sk.abstr.buildNativeClass('OSError', {
      constructor: function OSError(...args) { Sk.builtin.IOError.apply(this, args); },
      base: Sk.builtin.IOError,
    });
  }
  function nativeModule() {
    const error = Sk.abstr.buildNativeClass('UnsupportedOperation', {
      constructor: function UnsupportedOperation(...args) { Sk.builtin.IOError.apply(this, args); },
      base: Sk.builtins.OSError,
    });
    // These exceptions have identical storage. Skulpt's Python class builder
    // rejects their multiple native bases, so supply the standard exception MRO.
    error.prototype.sk$prototypical = false;
    error.prototype.tp$bases = [Sk.builtins.OSError, Sk.builtin.ValueError];
    error.prototype.tp$mro = [error, Sk.builtins.OSError, Sk.builtin.IOError,
      Sk.builtin.ValueError, Sk.builtin.Exception, Sk.builtin.BaseException, Sk.builtin.object];
    return {UnsupportedOperation:error};
  }
  library['src/lib/_movie_io.js'] = 'var $builtinmodule = ' + nativeModule.toString() + ';';
  library['src/lib/io.py'] = String.raw`
from _movie_io import UnsupportedOperation
SEEK_SET, SEEK_CUR, SEEK_END = 0, 1, 2
_LIMIT = 2 * 1024 * 1024

def _index(value):
    if isinstance(value, int): return value
    if hasattr(value, '__index__'):
        result = value.__index__()
        if isinstance(result, int): return result
    raise TypeError('an integer is required')

class _MemoryStream:
    @property
    def closed(self): return self._closed
    def _check(self):
        if self.closed: raise ValueError('I/O operation on closed file')
    def close(self):
        self._closed = True
        self._data = self._empty
    def flush(self): self._check()
    def readable(self): self._check(); return True
    def writable(self): self._check(); return True
    def seekable(self): self._check(); return True
    def isatty(self): self._check(); return False
    def fileno(self): self._check(); raise UnsupportedOperation('fileno')
    def __enter__(self): self._check(); return self
    def __exit__(self, exc_type, exc, tb): self.close(); return False
    def __iter__(self):
        if self._text: self._check()
        return self
    def __next__(self):
        line = self.readline()
        if not line: raise StopIteration
        return line
    def tell(self): self._check(); return self._position
    def getvalue(self): self._check(); return self._data
    def seek(self, offset, whence=SEEK_SET):
        self._check(); offset, whence = _index(offset), _index(whence)
        if whence not in (SEEK_SET, SEEK_CUR, SEEK_END): raise ValueError('invalid whence')
        if self._text and whence != SEEK_SET and offset != 0:
            raise OSError("Can't do nonzero cur-relative seeks")
        if whence == SEEK_SET:
            if offset < 0: raise ValueError('negative seek position')
            position = offset
        else: position = max(0, (self._position if whence == SEEK_CUR else len(self._data)) + offset)
        self._position = position
        return position
    def read(self, size=-1):
        self._check(); size = -1 if size is None else _index(size)
        start = self._position
        end = len(self._data) if size < 0 else min(len(self._data), start + size)
        self._position = max(start, end)
        return self._data[start:end]
    def readline(self, size=-1):
        self._check(); size = -1 if size is None else _index(size)
        start = self._position
        end = len(self._data) if size < 0 else min(len(self._data), start + size)
        newline = self._data.find(self._newline, start, end)
        if newline >= 0: end = newline + 1
        self._position = max(start, end)
        return self._data[start:end]
    def readlines(self, hint=-1):
        self._check(); hint = -1 if hint is None else _index(hint)
        lines, total = [], 0
        for line in self:
            lines.append(line); total += len(line)
            if hint > 0 and total > hint: break
        return lines
    def write(self, value):
        self._check(); value = self._validate(value)
        size = len(value); start = self._position
        end = start + size
        if end > _LIMIT: raise ValueError('in-memory stream exceeds local size limit')
        if size:
            gap = self._zero * max(0, start - len(self._data))
            self._data = self._data[:start] + gap + value + self._data[end:]
            self._position = end
        return size
    def writelines(self, lines):
        self._check()
        for line in lines: self.write(line)
    def truncate(self, size=None):
        self._check(); size = self._position if size is None else _index(size)
        if size < 0: raise ValueError('negative size value')
        self._data = self._data[:size]
        return size

class StringIO(_MemoryStream):
    _text, _empty, _zero, _newline = True, '', '\x00', '\n'
    encoding, errors, newlines = None, None, None
    def __init__(self, initial_value='', newline='\n'):
        if newline != '\n': raise NotImplementedError('StringIO supports newline="\\n" only')
        initial_value = '' if initial_value is None else self._validate(initial_value)
        if len(initial_value) > _LIMIT: raise ValueError('in-memory stream exceeds local size limit')
        self._data, self._position, self._closed = initial_value, 0, False
    def _validate(self, value):
        if not isinstance(value, str): raise TypeError('string argument expected')
        return value

class BytesIO(_MemoryStream):
    _text, _empty, _zero, _newline = False, b'', b'\x00', b'\n'
    def __init__(self, initial_bytes=b''):
        initial_bytes = b'' if initial_bytes is None else self._validate(initial_bytes)
        if len(initial_bytes) > _LIMIT: raise ValueError('in-memory stream exceeds local size limit')
        self._data, self._position, self._closed = initial_bytes, 0, False
    def _validate(self, value):
        if not isinstance(value, bytes): raise TypeError('a bytes object is required')
        return value
    def read1(self, size=-1): return self.read(size)
    def getbuffer(self): self._check(); raise UnsupportedOperation('mutable buffer views are unavailable')
    def readinto(self, buffer): self._check(); raise UnsupportedOperation('mutable buffer writes are unavailable')
    def readinto1(self, buffer): return self.readinto(buffer)
`;
}
