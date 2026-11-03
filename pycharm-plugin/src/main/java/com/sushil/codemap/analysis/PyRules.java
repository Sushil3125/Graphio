package com.sushil.codemap.analysis;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Constants of analyzer.py (framework names, builtins) used by the PSI port. */
final class PyRules {
    private PyRules() {}

    static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete", "options", "head", "trace");
    static final Set<String> ROUTE_ATTRS;
    static {
        Set<String> s = new HashSet<>(HTTP_METHODS);
        s.addAll(Set.of("api_route", "route", "websocket", "websocket_route"));
        ROUTE_ATTRS = Set.copyOf(s);
    }
    static final Set<String> DEPENDS_NAMES = Set.of("Depends", "Security");
    static final Set<String> CELERY_CALL_ATTRS = Set.of("delay", "apply_async", "s", "si", "signature", "map", "starmap");
    static final Set<String> APP_TYPES = Set.of("FastAPI", "Flask", "Starlette", "Quart", "Sanic", "Celery");
    /** {@code re.match(r"create_\w*app\w*$")}: anchored at the start, so use {@code matcher().matches()}. */
    static final Pattern APP_FACTORY_RE = Pattern.compile("create_\\w*app\\w*");
    static final Set<String> WRAPPER_ANNOTATIONS = Set.of("Optional", "Annotated", "ClassVar", "Final", "Required",
            "NotRequired", "ReadOnly");
    static final int MAX_EXTERNAL = 50;
    static final Set<String> HOOK_ATTRS = Set.of("exception_handler", "errorhandler", "middleware", "on_event",
            "listens_for", "before_request", "after_request", "teardown_request", "before_app_request",
            "after_app_request", "app_errorhandler", "on_after_configure", "on_after_finalize");
    static final Set<String> NO_REF_CALLS = Set.of("isinstance", "issubclass", "type", "getattr", "setattr", "hasattr",
            "super", "cast", "TypeVar", "NewType");

    /** {@code dir(builtins)} (CPython 3.13), used when PyCharm has no SDK to resolve builtins. */
    static final Set<String> BUILTIN_NAMES = Set.of((
            "ArithmeticError AssertionError AttributeError BaseException BaseExceptionGroup BlockingIOError "
            + "BrokenPipeError BufferError BytesWarning ChildProcessError ConnectionAbortedError ConnectionError "
            + "ConnectionRefusedError ConnectionResetError DeprecationWarning EOFError Ellipsis EncodingWarning "
            + "EnvironmentError Exception ExceptionGroup False FileExistsError FileNotFoundError FloatingPointError "
            + "FutureWarning GeneratorExit IOError ImportError ImportWarning IndentationError IndexError "
            + "InterruptedError IsADirectoryError KeyError KeyboardInterrupt LookupError MemoryError "
            + "ModuleNotFoundError NameError None NotADirectoryError NotImplemented NotImplementedError OSError "
            + "OverflowError PendingDeprecationWarning PermissionError ProcessLookupError PythonFinalizationError "
            + "RecursionError ReferenceError ResourceWarning RuntimeError RuntimeWarning StopAsyncIteration "
            + "StopIteration SyntaxError SyntaxWarning SystemError SystemExit TabError TimeoutError True TypeError "
            + "UnboundLocalError UnicodeDecodeError UnicodeEncodeError UnicodeError UnicodeTranslateError "
            + "UnicodeWarning UserWarning ValueError Warning ZeroDivisionError __build_class__ __debug__ __doc__ "
            + "__import__ __loader__ __name__ __package__ __spec__ abs aiter all anext any ascii bin bool breakpoint "
            + "bytearray bytes callable chr classmethod compile complex copyright credits delattr dict dir divmod "
            + "enumerate eval exec exit filter float format frozenset getattr globals hasattr hash help hex id input "
            + "int isinstance issubclass iter len license list locals map max memoryview min next object oct open ord "
            + "pow print property quit range repr reversed round set setattr slice sorted staticmethod str sum super "
            + "tuple type vars zip").split(" "));

    /** Builtins that are types (subset of {@link #BUILTIN_NAMES}). */
    static final Set<String> BUILTIN_TYPES = Set.of((
            "ArithmeticError AssertionError AttributeError BaseException BaseExceptionGroup BlockingIOError "
            + "BrokenPipeError BufferError BytesWarning ChildProcessError ConnectionAbortedError ConnectionError "
            + "ConnectionRefusedError ConnectionResetError DeprecationWarning EOFError EncodingWarning "
            + "EnvironmentError Exception ExceptionGroup FileExistsError FileNotFoundError FloatingPointError "
            + "FutureWarning GeneratorExit IOError ImportError ImportWarning IndentationError IndexError "
            + "InterruptedError IsADirectoryError KeyError KeyboardInterrupt LookupError MemoryError "
            + "ModuleNotFoundError NameError NotADirectoryError NotImplementedError OSError OverflowError "
            + "PendingDeprecationWarning PermissionError ProcessLookupError PythonFinalizationError RecursionError "
            + "ReferenceError ResourceWarning RuntimeError RuntimeWarning StopAsyncIteration StopIteration "
            + "SyntaxError SyntaxWarning SystemError SystemExit TabError TimeoutError TypeError UnboundLocalError "
            + "UnicodeDecodeError UnicodeEncodeError UnicodeError UnicodeTranslateError UnicodeWarning UserWarning "
            + "ValueError Warning ZeroDivisionError __loader__ bool bytearray bytes classmethod complex dict "
            + "enumerate filter float frozenset int list map memoryview object property range reversed set slice "
            + "staticmethod str super tuple type zip").split(" "));
}
