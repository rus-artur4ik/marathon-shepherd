package dev.shepherd.protocol

import kotlinx.serialization.Serializable

/** A person who signs in, as opposed to an API client. */
@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val displayName: String? = null,
    val email: String? = null,
    /** `local`, `ldap` or `oidc`. */
    val source: String,
    /** The OIDC provider id, or `ldap`, for people from a directory. */
    val provider: String? = null,
    /** admin, user or viewer. */
    val role: String,
    /** True when directory groups decide the role at every sign-in, so it cannot be edited here. */
    val roleManagedByProvider: Boolean = false,
    /** Limits set on this user explicitly. */
    val quota: QuotaDto = QuotaDto(),
    /** Limits in force, with the configured defaults applied. */
    val effectiveQuota: QuotaDto = QuotaDto(),
    val active: Boolean,
    /** Set for a new or reset password: the user must choose their own before doing anything else. */
    val mustChangePassword: Boolean = false,
    val createdAt: String,
    val createdBy: String? = null,
    val lastLoginAt: String? = null,
    val disabledAt: String? = null
)

/** Body of `POST /api/v1/admin/users`: a local user. Without [password] a temporary one is generated. */
@Serializable
data class CreateUserRequest(
    val username: String,
    val password: String? = null,
    val displayName: String? = null,
    val email: String? = null,
    val role: String = "user",
    val quota: QuotaDto = QuotaDto()
)

@Serializable
data class CreatedUserResponse(
    val user: UserDto,
    /** Only when the manager generated the password; shown this once. */
    val temporaryPassword: String? = null
)

/** Body of `PATCH /api/v1/admin/users/{id}`; absent fields stay as they are. */
@Serializable
data class UpdateUserRequest(
    val displayName: String? = null,
    val email: String? = null,
    val role: String? = null,
    /** Replaces the whole quota when present. */
    val quota: QuotaDto? = null,
    /** false disables the user, true enables them again. */
    val active: Boolean? = null
)

/** Body of `POST /api/v1/admin/users/{id}/password`. Without [password] a temporary one is generated. */
@Serializable
data class ResetPasswordRequest(
    val password: String? = null
)

@Serializable
data class PasswordResetResponse(
    /** Only when the manager generated the password; shown this once. */
    val temporaryPassword: String? = null
)

/** How this manager lets people sign in, for the sign-in page. */
@Serializable
data class AuthMethodsResponse(
    val local: Boolean,
    val ldap: Boolean,
    val providers: List<AuthProviderDto> = emptyList(),
    /** Why the last OIDC sign-in in this browser failed; returned once, then forgotten. */
    val signInError: String? = null
)

@Serializable
data class AuthProviderDto(
    val id: String,
    val displayName: String,
    /** Open this in the browser to sign in; `returnTo` may be appended. */
    val loginUrl: String
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

/** The signed-in browser session. [csrfToken] goes into the `X-CSRF-Token` header of every change. */
@Serializable
data class WebSessionResponse(
    val user: UserDto,
    val csrfToken: String,
    val expiresAt: String
)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)

/** Body of `POST /api/v1/auth/password`: change a password without a session, e.g. a temporary one from the CLI. */
@Serializable
data class ChangePasswordWithLoginRequest(
    val username: String,
    val currentPassword: String,
    val newPassword: String
)

/** A personal API token: the key a person uses from mshctl, scripts or agents. */
@Serializable
data class PersonalTokenDto(
    val id: String,
    val name: String,
    /** First characters of the token, to tell tokens apart. */
    val prefix: String,
    val createdAt: String,
    val lastUsedAt: String? = null,
    val expiresAt: String? = null,
    val revokedAt: String? = null
)

@Serializable
data class CreateTokenRequest(
    val name: String,
    /** Unset never expires. */
    val expiresInDays: Int? = null
)

/** Body of `POST /api/v1/auth/tokens`: sign in with a password and get a personal token back. */
@Serializable
data class TokenLoginRequest(
    val username: String,
    val password: String,
    val name: String = "mshctl",
    val expiresInDays: Int? = 90
)

@Serializable
data class IssuedTokenResponse(
    val token: PersonalTokenDto,
    /** The token itself, shown only this once. */
    val secret: String
)
