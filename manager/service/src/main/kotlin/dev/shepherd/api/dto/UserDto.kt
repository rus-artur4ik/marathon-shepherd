package dev.shepherd.api.dto

import dev.shepherd.infra.auth.Accounts
import dev.shepherd.infra.auth.IssuedUserToken
import dev.shepherd.infra.auth.StartedWebSession
import dev.shepherd.infra.auth.UserRecord
import dev.shepherd.infra.auth.UserTokenRecord
import dev.shepherd.infra.auth.WebSessionRecord
import dev.shepherd.protocol.IssuedTokenResponse
import dev.shepherd.protocol.PersonalTokenDto
import dev.shepherd.protocol.UserDto
import dev.shepherd.protocol.WebSessionResponse

fun UserRecord.toDto(accounts: Accounts): UserDto = UserDto(
    id = id,
    username = username,
    displayName = displayName,
    email = email,
    source = source.wireName,
    provider = provider,
    role = role.wireName,
    roleManagedByProvider = roleManagedByProvider,
    quota = quota.toDto(),
    effectiveQuota = accounts.effectiveQuota(this).toDto(),
    active = isActive,
    mustChangePassword = mustChangePassword,
    createdAt = createdAt.toString(),
    createdBy = createdBy,
    lastLoginAt = lastLoginAt?.toString(),
    disabledAt = disabledAt?.toString()
)

fun UserTokenRecord.toDto(): PersonalTokenDto = PersonalTokenDto(
    id = id,
    name = name,
    prefix = prefix,
    createdAt = createdAt.toString(),
    lastUsedAt = lastUsedAt?.toString(),
    expiresAt = expiresAt?.toString(),
    revokedAt = revokedAt?.toString()
)

fun IssuedUserToken.toResponse(): IssuedTokenResponse = IssuedTokenResponse(token = token.toDto(), secret = secret)

fun WebSessionRecord.toResponse(user: UserRecord, accounts: Accounts): WebSessionResponse =
    WebSessionResponse(user = user.toDto(accounts), csrfToken = csrfToken, expiresAt = expiresAt.toString())

fun StartedWebSession.toResponse(accounts: Accounts): WebSessionResponse = session.toResponse(user, accounts)
