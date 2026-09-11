"""Exceptions raised by the client.

Every error response from the manager becomes a :class:`ShepherdError`. The subclass
says what went wrong without comparing status codes, and ``message`` is the manager's
``error`` field, written to be shown to a person as-is. No exception ever carries the
API key.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Dict, Optional, Type

if TYPE_CHECKING:
    from .models import Session


class ShepherdError(Exception):
    """Base class for every error the client raises on purpose.

    ``status`` is the HTTP status, or ``None`` when the error did not come from a
    response (:class:`QueueTimeout`, :class:`SessionFailed`). Network failures are not
    wrapped: they surface as the standard ``urllib.error.URLError``/``OSError``.
    """

    def __init__(self, status: Optional[int], message: str) -> None:
        super().__init__(status, message)
        self.status = status
        self.message = message

    def __str__(self) -> str:
        if self.status is None:
            return self.message
        return f"{self.message} (HTTP {self.status})"


class BadRequest(ShepherdError):
    """400: the request is malformed or fails validation, e.g. a bad API level selector."""


class Unauthorized(ShepherdError):
    """401: no API key, or an unknown or revoked one."""


class Forbidden(ShepherdError):
    """403: the key's role does not allow this, or the session belongs to another client."""


class NotFound(ShepherdError):
    """404: no such session, device, client or provider."""


class Conflict(ShepherdError):
    """409: the change conflicts with the current state, e.g. leases that cannot be renewed."""


class QuotaExceeded(ShepherdError):
    """429: the client's device quota is used up; release something or wait."""


class Unavailable(ShepherdError):
    """503: the manager cannot serve this, e.g. no registered device can ever match."""


class QueueTimeout(ShepherdError):
    """A queued session was not READY within the helper's ``queue_timeout``.

    The helper has already released the session; ``session`` is the last state it saw.
    """

    def __init__(self, message: str, session: Optional[Session] = None) -> None:
        super().__init__(None, message)
        self.session = session
        self.args = (message, session)  # what pickle passes back to __init__


class SessionFailed(ShepherdError):
    """A queued session ended FAILED, EXPIRED or RELEASED instead of becoming READY.

    The helper has already released it; ``session`` is its final state.
    """

    def __init__(self, message: str, session: Optional[Session] = None) -> None:
        super().__init__(None, message)
        self.session = session
        self.args = (message, session)


_BY_STATUS: Dict[int, Type[ShepherdError]] = {
    400: BadRequest,
    401: Unauthorized,
    403: Forbidden,
    404: NotFound,
    409: Conflict,
    429: QuotaExceeded,
    503: Unavailable,
}


def error_for_status(status: int, message: str) -> ShepherdError:
    """The exception for an error response; statuses without a subclass get the base class."""
    return _BY_STATUS.get(status, ShepherdError)(status, message)
