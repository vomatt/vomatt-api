# Implementation Plan: Vote Comment Feature

## Overview
Adding comment functionality to the voting system, allowing users to discuss votes.

## Stage 1: Database Schema
**Goal**: Create database table for vote comments
**Success Criteria**:
- Migration file created and executed successfully
- Table `vote_comments` exists with proper constraints and indexes
**Tests**: Manual verification via database inspection
**Status**: Not Started

### Details:
- Create migration file V5__add_vote_comments.sql
- Table structure:
  - id (BIGSERIAL PRIMARY KEY)
  - vote_id (VARCHAR(36), FK to votes)
  - user_id (VARCHAR(36), FK to users)
  - parent_comment_id (BIGINT, FK to vote_comments, nullable for nested comments)
  - content (TEXT, NOT NULL)
  - created_at (TIMESTAMP)
  - updated_at (TIMESTAMP)
  - is_deleted (BOOLEAN, for soft delete)
- Indexes on vote_id, user_id, parent_comment_id, created_at

## Stage 2: Domain Layer
**Goal**: Implement VoteComment entity and repository
**Success Criteria**:
- VoteComment entity follows project JPA patterns
- Repository interface created with required query methods
- Code compiles successfully
**Tests**: Unit tests for entity relationships
**Status**: Not Started

### Details:
- Create VoteComment.java entity in domain/vote package
- Follow existing patterns from Vote.java and UserVote.java
- Implement VoteCommentRepository.java in domain/vote/repository
- Support for nested comments (replies)
- Soft delete support

## Stage 3: Service Layer
**Goal**: Implement comment business logic
**Success Criteria**:
- Service interface and implementation created
- All CRUD operations working
- Proper authorization checks
**Tests**: Service layer unit tests
**Status**: Not Started

### Details:
- Create VoteCommentService interface
- Create VoteCommentServiceImpl
- Operations:
  - createComment(voteId, userId, content, parentCommentId?)
  - getCommentsByVote(voteId, pageable)
  - updateComment(commentId, userId, newContent)
  - deleteComment(commentId, userId)
  - getReplies(parentCommentId, pageable)
- Authorization: users can only edit/delete their own comments

## Stage 4: API Layer
**Goal**: Create REST endpoints for comments
**Success Criteria**:
- All endpoints working with proper validation
- Swagger documentation complete
- Error handling follows project patterns
**Tests**: Integration tests for all endpoints
**Status**: Not Started

### Details:
- Add to VoteController or create VoteCommentController
- Endpoints:
  - POST /api/v1/votes/{voteId}/comments - Create comment
  - GET /api/v1/votes/{voteId}/comments - List comments (paginated)
  - GET /api/v1/votes/{voteId}/comments/{commentId}/replies - Get replies
  - PUT /api/v1/votes/{voteId}/comments/{commentId} - Update comment
  - DELETE /api/v1/votes/{voteId}/comments/{commentId} - Delete comment
- DTOs: CreateCommentRequest, UpdateCommentRequest, CommentResponse
- Follow existing error handling patterns (ErrorType enum)

## Stage 5: Testing & Validation
**Goal**: Comprehensive test coverage
**Success Criteria**:
- All tests passing
- No linter/formatter warnings
- Code follows project conventions
**Tests**: All automated tests pass
**Status**: Not Started

### Details:
- Repository tests
- Service layer tests
- Controller/API tests
- Edge cases: deleted votes, nested comment limits, authorization
