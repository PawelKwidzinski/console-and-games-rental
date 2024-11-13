package pl.kwidz.cgr.game;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import pl.kwidz.cgr.common.PageResponse;
import pl.kwidz.cgr.exception.OperationNotPermittedException;
import pl.kwidz.cgr.file.FileStorageService;
import pl.kwidz.cgr.history.GameTransactionHistory;
import pl.kwidz.cgr.history.GameTransactionHistoryRepository;
import pl.kwidz.cgr.user.User;

import java.util.List;
import java.util.Objects;

import static pl.kwidz.cgr.game.GameSpecification.withOwnerId;

@Service
@RequiredArgsConstructor
public class GameService {

    private final GameRepository gameRepository;
    private final GameMapper gameMapper;
    private final GameTransactionHistoryRepository transactionHistoryRepository;
    private final FileStorageService fileStorageService;

    public Integer save(GameRequest gameRequest, Authentication connectedUser) {
        User user = (User) connectedUser.getPrincipal();
        Game game = gameMapper.toGame(gameRequest);
        game.setOwner(user);
        return gameRepository.save(game).getId();
    }

    public GameResponse findById(Integer gameId) {
        return gameRepository.findById(gameId)
                .map(gameMapper::toBookResponse)
                .orElseThrow(() -> new EntityNotFoundException(String.format("No game found with the ID: %d", gameId)));
    }

    public PageResponse<GameResponse> findAllGames(int page, int size, Authentication connectedUser) {
        User user = (User) connectedUser.getPrincipal();
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdDate").descending());
        Page<Game> games = gameRepository.findAllDisplayableGames(pageable, user.getId());
        List<GameResponse> gameResponses = games.stream()
                .map(gameMapper::toBookResponse)
                .toList();
        return new PageResponse<>(gameResponses, games.getNumber(), games.getSize(), games.getTotalElements(),
                games.getTotalPages(), games.isFirst(), games.isLast());
    }

    public PageResponse<GameResponse> findGamesByOwner(int page, int size, Authentication connectedUser) {
        User user = (User) connectedUser.getPrincipal();
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdDate").descending());
        Page<Game> games = gameRepository.findAll(withOwnerId(user.getId()), pageable);

        List<GameResponse> gameResponses = games.stream()
                .map(gameMapper::toBookResponse)
                .toList();
        return new PageResponse<>(gameResponses, games.getNumber(), games.getSize(), games.getTotalElements(),
                games.getTotalPages(), games.isFirst(), games.isLast());
    }

    public PageResponse<BorrowedGameResponse> findAllBorrowedGames(int page, int size, Authentication connectedUser) {
        User user = (User) connectedUser.getPrincipal();
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdDate").descending());
        Page<GameTransactionHistory> allBorrowedGames = transactionHistoryRepository.findAllBorrowedGames(pageable, user.getId());

        List<BorrowedGameResponse> gameResponses = allBorrowedGames.stream()
                .map(gameMapper::toBorrowedGameResponse)
                .toList();
        return new PageResponse<>(gameResponses, allBorrowedGames.getNumber(), allBorrowedGames.getSize(), allBorrowedGames.getTotalElements(),
                allBorrowedGames.getTotalPages(), allBorrowedGames.isFirst(), allBorrowedGames.isLast());
    }

    public PageResponse<BorrowedGameResponse> findAllReturnedGames(int page, int size, Authentication connectedUser) {
        User user = (User) connectedUser.getPrincipal();
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdDate").descending());
        Page<GameTransactionHistory> allReturnedGames = transactionHistoryRepository.findAllReturnedGames(pageable, user.getId());

        List<BorrowedGameResponse> gameResponses = allReturnedGames.stream()
                .map(gameMapper::toBorrowedGameResponse)
                .toList();
        return new PageResponse<>(gameResponses, allReturnedGames.getNumber(), allReturnedGames.getSize(), allReturnedGames.getTotalElements(),
                allReturnedGames.getTotalPages(), allReturnedGames.isFirst(), allReturnedGames.isLast());
    }


    public Integer updateShareableStatus(Integer gameId, Authentication connectedUser) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new EntityNotFoundException(String.format("No game found with the ID: %d", gameId)));
        User user = (User) connectedUser.getPrincipal();

        if (!Objects.equals(game.getOwner().getId(), user.getId())) {
            throw new OperationNotPermittedException("You cannot update other games shareable status");
        }

        game.setShareable(!game.isShareable());
        gameRepository.save(game);
        return gameId;
    }

    public Integer updateArchiveStatus(Integer gameId, Authentication connectedUser) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new EntityNotFoundException(String.format("No game found with the ID: %d", gameId)));
        User user = (User) connectedUser.getPrincipal();

        if (!Objects.equals(game.getOwner().getId(), user.getId())) {
            throw new OperationNotPermittedException("You cannot update others games archived status");
        }

        game.setArchived(!game.isArchived());
        gameRepository.save(game);
        return gameId;
    }

    public Integer borrowGame(Integer gameId, Authentication connectedUser) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new EntityNotFoundException(String.format("No game found with the ID: %d", gameId)));
        if (game.isArchived() || !game.isShareable()) {
            throw new OperationNotPermittedException("You cannot borrow this game since it is archived or not shareable");
        }

        User user = (User) connectedUser.getPrincipal();
        if (Objects.equals(game.getOwner().getId(), user.getId())) {
            throw new OperationNotPermittedException("You cannot borrow if you're the owner of this game");
        }

        final boolean isAlreadyBorrowed = transactionHistoryRepository.isAlreadyBorrowedByUser(gameId, user.getId());
        if (isAlreadyBorrowed) {
            throw new OperationNotPermittedException("This game is already borrowed");
        }

        GameTransactionHistory gameTransactionHistory = GameTransactionHistory.builder()
                .user(user)
                .game(game)
                .returned(false)
                .returnApproved(false)
                .build();
        return transactionHistoryRepository.save(gameTransactionHistory).getId();
    }

    public Integer returnBorrowedGame(Integer gameId, Authentication connectedUser) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new EntityNotFoundException(String.format("No game found with the ID: %d", gameId)));
        if (game.isArchived() || !game.isShareable()) {
            throw new OperationNotPermittedException("You cannot return this game since it is archived or not shareable");
        }

        User user = (User) connectedUser.getPrincipal();
        if (Objects.equals(game.getOwner().getId(), user.getId())) {
            throw new OperationNotPermittedException("You cannot borrow or return your own game");
        }

        GameTransactionHistory gameTransactionHistory = transactionHistoryRepository.findByGameIdAndUserId(gameId, user.getId())
                .orElseThrow(() -> new OperationNotPermittedException(String.format("You did not borrow this game with ID: %d", gameId)));

        gameTransactionHistory.setReturned(true);
        return transactionHistoryRepository.save(gameTransactionHistory).getId();
    }

    public Integer approveReturnBorrowedGame(Integer gameId, Authentication connectedUser) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new EntityNotFoundException(String.format("No game found with the ID: %d", gameId)));
        if (game.isArchived() || !game.isShareable()) {
            throw new OperationNotPermittedException("You cannot return this game since it is archived or not shareable");
        }

        User user = (User) connectedUser.getPrincipal();
        if (Objects.equals(game.getOwner().getId(), user.getId())) {
            throw new OperationNotPermittedException("You cannot borrow or return your own game");
        }
        GameTransactionHistory gameTransactionHistory = transactionHistoryRepository.findByGameIdAndOwnerId(gameId, user.getId())
                .orElseThrow(() -> new OperationNotPermittedException(
                        String.format("The Game is not returned yet. You cannot approve it's return game with ID: %d", gameId)));
        gameTransactionHistory.setReturnApproved(true);

        return transactionHistoryRepository.save(gameTransactionHistory).getId();
    }

    public void uploadBookCoverPicture(MultipartFile file, Authentication connectedUser, Integer gameId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new EntityNotFoundException(String.format("No game found with the ID: %d", gameId)));
        User user = (User) connectedUser.getPrincipal();
        var gameCover = fileStorageService.saveFile(file, user.getId());
        game.setGameCover(gameCover);
        gameRepository.save(game);
    }

}