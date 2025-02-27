package org.enso.searcher.sql

import org.enso.polyglot.runtime.Runtime.Api._
import org.enso.polyglot.{ExportedSymbol, Suggestion}
import org.enso.searcher.data.QueryResult
import org.enso.searcher.{SuggestionEntry, SuggestionsRepo}
import slick.jdbc.SQLiteProfile.api._
import slick.jdbc.meta.MTable
import slick.relational.RelationalProfile

import java.util.UUID

import scala.collection.immutable.HashMap
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** The object for accessing the suggestions database. */
final class SqlSuggestionsRepo(val db: SqlDatabase)(implicit
  ec: ExecutionContext
) extends SuggestionsRepo[Future] {

  /** Initialize the repo. */
  override def init: Future[Unit] =
    db.run(initQuery)

  /** @inheritdoc */
  override def clean: Future[Unit] =
    db.run(cleanQuery)

  /** @inheritdoc */
  override def getAll: Future[(Long, Seq[SuggestionEntry])] =
    db.run(getAllQuery)

  /** @inheritdoc */
  override def search(
    module: Option[String],
    selfType: Seq[String],
    returnType: Option[String],
    kinds: Option[Seq[Suggestion.Kind]],
    position: Option[Suggestion.Position],
    isStatic: Option[Boolean]
  ): Future[(Long, Seq[Long])] =
    db.run(searchQuery(module, selfType, returnType, kinds, position, isStatic))

  /** @inheritdoc */
  override def select(id: Long): Future[Option[Suggestion]] =
    db.run(selectQuery(id))

  /** @inheritdoc */
  override def insert(suggestion: Suggestion): Future[Option[Long]] =
    db.run(insertQuery(suggestion))

  /** @inheritdoc */
  override def insertAll(
    suggestions: Seq[Suggestion]
  ): Future[(Long, Seq[Long])] =
    db.run(insertAllWithVersionQuery(suggestions).transactionally)

  /** @inheritdoc */
  override def applyTree(
    tree: Seq[SuggestionUpdate]
  ): Future[Seq[QueryResult[SuggestionUpdate]]] =
    db.run(applyTreeQuery(tree).transactionally)

  /** @inheritdoc */
  override def applyActions(
    actions: Seq[SuggestionsDatabaseAction]
  ): Future[Seq[QueryResult[SuggestionsDatabaseAction]]] =
    db.run(applyActionsQuery(actions).transactionally)

  /** @inheritdoc */
  override def applyExports(
    updates: Seq[ExportsUpdate]
  ): Future[Seq[QueryResult[ExportsUpdate]]] =
    db.run(applyExportsQuery(updates).transactionally)

  /** @inheritdoc */
  override def remove(suggestion: Suggestion): Future[Option[Long]] =
    db.run(removeQuery(suggestion))

  /** @inheritdoc */
  override def removeModules(modules: Seq[String]): Future[(Long, Seq[Long])] =
    db.run(removeByModuleQuery(modules))

  /** @inheritdoc */
  override def update(
    suggestion: Suggestion,
    externalId: Option[Option[Suggestion.ExternalID]],
    returnType: Option[String],
    documentation: Option[Option[String]],
    scope: Option[Suggestion.Scope],
    reexport: Option[Option[String]]
  ): Future[(Long, Option[Long])] =
    db.run(
      updateQuery(
        suggestion,
        externalId,
        returnType,
        documentation,
        scope,
        reexport
      )
    )

  /** @inheritdoc */
  override def updateAll(
    expressions: Seq[(Suggestion.ExternalID, String)]
  ): Future[(Long, Seq[Option[Long]])] =
    db.run(updateAllQuery(expressions).transactionally)

  /** @inheritdoc */
  override def currentVersion: Future[Long] =
    db.run(currentVersionQuery)

  /** Close the database. */
  def close(): Unit =
    db.close()

  /** Get the database schema version.
    *
    * @return the schema version of the database
    */
  def getSchemaVersion: Future[Long] =
    db.run(currentSchemaVersionQuery)

  /** Set the database schema version.
    *
    * @param version the database schema version
    * @return the schema version of the database
    */
  def setSchemaVersion(version: Long): Future[Long] =
    db.run(setSchemaVersionQuery(version))

  /** Remove the database schema version. */
  def clearSchemaVersion: Future[Unit] =
    db.run(clearSchemaVersionQuery)

  def insertBatchJava(suggestions: Array[Suggestion]): Future[Int] =
    db.run(insertBatchJavaQuery(suggestions).transactionally)

  def selectAllSuggestions: Future[Seq[SuggestionEntry]] =
    db.run(selectAllSuggestionsQuery.transactionally)

  /** The query to initialize the repo. */
  private def initQuery: DBIO[Unit] = {
    type RelationalTable[A] = RelationalProfile#Table[A]
    def checkVersion(version: Long) =
      if (version == SchemaVersion.CurrentVersion) {
        DBIO.successful(())
      } else {
        DBIO.failed(new InvalidSchemaVersion(version))
      }
    def createSchema(table: TableQuery[RelationalTable[_]]) =
      for {
        tables <- MTable.getTables(table.shaped.value.tableName)
        _      <- if (tables.isEmpty) table.schema.create else DBIO.successful(())
      } yield ()

    val tables: Seq[TableQuery[RelationalTable[_]]] =
      Seq(Suggestions, SuggestionsVersion, SchemaVersion)
        .asInstanceOf[Seq[TableQuery[RelationalTable[_]]]]
    val initSchemas =
      for {
        _       <- DBIO.sequence(tables.map(createSchema))
        version <- initSchemaVersionQuery
      } yield version

    for {
      versionAttempt <- currentSchemaVersionQuery.asTry
      version        <- versionAttempt.fold(_ => initSchemas, DBIO.successful)
      _              <- checkVersion(version)
    } yield ()
  }

  /** The query to clean the repo. */
  private def cleanQuery: DBIO[Unit] = {
    for {
      _ <- Suggestions.delete
      _ <- SuggestionsVersion.delete
    } yield ()
  }

  /** The query to get all suggestions.
    *
    * @return the current database version with the list of suggestion entries
    */
  private def getAllQuery: DBIO[(Long, Seq[SuggestionEntry])] = {
    for {
      rows    <- Suggestions.result
      version <- currentVersionQuery
    } yield (version, rows.map(toSuggestionEntry))
  }

  /** The query to search suggestion by various parameters.
    *
    * @param module the module name search parameter
    * @param selfType the selfType search parameter, ordered by specificity
    *                 with the most specific type first
    * @param returnType the returnType search parameter
    * @param kinds the list suggestion kinds to search
    * @param position the absolute position in the text
    * @param isStatic the static attiribute
    * @return the list of suggestion ids, ranked by specificity (as for
    *         `selfType`)
    */
  private def searchQuery(
    module: Option[String],
    selfType: Seq[String],
    returnType: Option[String],
    kinds: Option[Seq[Suggestion.Kind]],
    position: Option[Suggestion.Position],
    isStatic: Option[Boolean]
  ): DBIO[(Long, Seq[Long])] = {
    val typeSorterMap: HashMap[String, Int] = HashMap(selfType.zipWithIndex: _*)
    val searchAction =
      if (
        module.isEmpty &&
        selfType.isEmpty &&
        returnType.isEmpty &&
        kinds.isEmpty &&
        position.isEmpty &&
        isStatic.isEmpty
      ) {
        DBIO.successful(Seq())
      } else {
        val query =
          searchQueryBuilder(
            module,
            selfType,
            returnType,
            kinds,
            position,
            isStatic
          )
            .map(r => (r.id, r.selfType))
        query.result
      }
    val query = for {
      resultsWithTypes <- searchAction
      // This implementation should be revisited if it ever becomes a
      // performance bottleneck. It may be possible to encode the same logic in
      // the database query itself.
      results = resultsWithTypes
        .sortBy { case (_, ty) => typeSorterMap.getOrElse(ty, -1) }
        .map(_._1)
      version <- currentVersionQuery
    } yield (version, results)
    query
  }

  /** The query to select the suggestion by id.
    *
    * @param id the id of a suggestion
    * @return return the suggestion
    */
  private def selectQuery(id: Long): DBIO[Option[Suggestion]] = {
    for {
      rows <- Suggestions.filter(_.id === id).result
    } yield rows.headOption.map(toSuggestion)
  }

  /** The query to insert the suggestion
    *
    * @param suggestion the suggestion to insert
    * @return the id of an inserted suggestion
    */
  private def insertQuery(suggestion: Suggestion): DBIO[Option[Long]] = {
    val suggestionRow = toSuggestionRow(suggestion)
    val query = for {
      id <- Suggestions.returning(Suggestions.map(_.id)) += suggestionRow
      _  <- incrementVersionQuery
    } yield id
    query.asTry.map {
      case Failure(_)  => None
      case Success(id) => Some(id)
    }
  }

  /** The query to apply the suggestion updates.
    *
    * @param tree the sequence of updates
    * @return the result of applying updates with the new database version
    */
  private def applyTreeQuery(
    tree: Seq[SuggestionUpdate]
  ): DBIO[Seq[QueryResult[SuggestionUpdate]]] = {
    val queries = tree.map {
      case update @ SuggestionUpdate(suggestion, action) =>
        val query = action match {
          case SuggestionAction.Add() =>
            insertQuery(suggestion)
          case SuggestionAction.Remove() =>
            removeQuery(suggestion)
          case SuggestionAction.Modify(
                extId,
                args,
                returnType,
                doc,
                scope,
                reexport
              ) =>
            if (
              extId.isDefined ||
              args.isDefined ||
              returnType.isDefined ||
              doc.isDefined ||
              scope.isDefined ||
              reexport.isDefined
            ) {
              updateSuggestionQuery(
                suggestion,
                extId,
                returnType,
                doc,
                scope,
                reexport
              )
            } else {
              DBIO.successful(None)
            }
        }
        query.map(rs => QueryResult(rs.toSeq, update))
    }
    DBIO.sequence(queries)
  }

  /** The query to apply the sequence of actions on the database.
    *
    * @param actions the list of actions
    * @return the result of applying actions
    */
  private def applyActionsQuery(
    actions: Seq[SuggestionsDatabaseAction]
  ): DBIO[Seq[QueryResult[SuggestionsDatabaseAction]]] = {
    val removeActions = actions.map {
      case act @ SuggestionsDatabaseAction.Clean(module) =>
        for {
          ids <- removeModulesQuery(Seq(module))
        } yield QueryResult[SuggestionsDatabaseAction](ids, act)
    }
    DBIO.sequence(removeActions)
  }

  /** The query that applies the sequence of export updates.
    *
    * @param updates the list of export updates
    * @return the result of applying actions
    */
  private def applyExportsQuery(
    updates: Seq[ExportsUpdate]
  ): DBIO[Seq[QueryResult[ExportsUpdate]]] = {
    def depth(module: String): Int =
      module.count(_ == '.')

    def updateSuggestionReexport(module: String, symbol: ExportedSymbol) = {
      val moduleDepth = depth(module)
      sql"""
          update suggestions
          set reexport = $module
          where module = ${symbol.module}
            and name = ${symbol.name}
            and kind = ${SuggestionKind(symbol.kind)}
            and (
              reexport is null or
              length(reexport) - length(replace(reexport, '.', '')) > $moduleDepth
            )
          returning id
         """.as[Long]
    }

    def unsetSuggestionReexport(module: String, symbol: ExportedSymbol) =
      sql"""
          update suggestions
          set reexport = null
          where module = ${symbol.module}
            and name = ${symbol.name}
            and kind = ${SuggestionKind(symbol.kind)}
            and reexport = $module
          returning id
         """.as[Long]

    val actions = updates.flatMap { update =>
      val symbols = update.exports.symbols.toSeq
      update.action match {
        case ExportsAction.Add() =>
          symbols.map { symbol =>
            for {
              ids <- updateSuggestionReexport(update.exports.module, symbol)
            } yield QueryResult(ids, update)
          }
        case ExportsAction.Remove() =>
          symbols.map { symbol =>
            for {
              ids <- unsetSuggestionReexport(update.exports.module, symbol)
            } yield QueryResult(ids, update)
          }
      }
    }

    for {
      rs <- DBIO.sequence(actions)
      _ <-
        if (rs.flatMap(_.ids).nonEmpty) incrementVersionQuery
        else DBIO.successful(())
    } yield rs
  }

  /** The query to select the suggestion.
    *
    * @param raw the suggestion converted to the row form
    * @return the database query
    */
  private def selectSuggestionQuery(
    raw: SuggestionRow
  ): Query[SuggestionsTable, SuggestionRow, Seq] = {
    Suggestions
      .filter(_.module === raw.module)
      .filter(_.kind === raw.kind)
      .filter(_.name === raw.name)
      .filter(_.selfType === raw.selfType)
      .filter(_.scopeStartLine === raw.scopeStartLine)
      .filter(_.scopeStartOffset === raw.scopeStartOffset)
      .filter(_.scopeEndLine === raw.scopeEndLine)
      .filter(_.scopeEndOffset === raw.scopeEndOffset)
  }

  /** The query to remove the suggestion.
    *
    * @param suggestion the suggestion to remove
    * @return the id of removed suggestion
    */
  private def removeQuery(suggestion: Suggestion): DBIO[Option[Long]] = {
    val raw         = toSuggestionRow(suggestion)
    val selectQuery = selectSuggestionQuery(raw)
    val deleteQuery = for {
      rows <- selectQuery.result
      n    <- selectQuery.delete
      _    <- if (n > 0) incrementVersionQuery else DBIO.successful(())
    } yield rows.flatMap(_.id).headOption
    deleteQuery
  }

  /** The query to remove the suggestions by module name
    *
    * @param modules the module names to remove
    * @return the current database version and a list of removed suggestion ids
    */
  private def removeByModuleQuery(
    modules: Seq[String]
  ): DBIO[(Long, Seq[Long])] = {
    val deleteQuery = for {
      ids <- removeModulesQuery(modules)
      version <-
        if (ids.nonEmpty) incrementVersionQuery else currentVersionQuery
    } yield version -> ids
    deleteQuery
  }

  /** The query to remove the suggestions by module name
    *
    * @param modules the module names to remove
    * @return the list of removed suggestion ids
    */
  private def removeModulesQuery(
    modules: Seq[String]
  ): DBIO[Seq[Long]] = {
    val selectQuery = Suggestions.filter(_.module.inSet(modules))
    for {
      rows <- selectQuery.map(_.id).result
      _    <- selectQuery.delete
    } yield rows
  }

  /** The query to update a suggestion.
    *
    * @param externalId the external id of a suggestion
    * @param returnType the new return type
    * @return the id of updated suggestion
    */
  private def updateByExternalIdQuery(
    externalId: Suggestion.ExternalID,
    returnType: String
  ): DBIO[Option[Long]] = {
    val selectQuery = Suggestions
      .filter { row =>
        row.externalIdLeast === externalId.getLeastSignificantBits &&
        row.externalIdMost === externalId.getMostSignificantBits
      }
    for {
      id <- selectQuery.map(_.id).result.headOption
      _  <- selectQuery.map(_.returnType).update(returnType)
    } yield id
  }

  /** The query to update the suggestion.
    *
    * @param suggestion the key suggestion
    * @param externalId the external id to update
    * @param returnType the return type to update
    * @param documentation the documentation string to update
    * @param scope the scope to update
    */
  private def updateQuery(
    suggestion: Suggestion,
    externalId: Option[Option[Suggestion.ExternalID]],
    returnType: Option[String],
    documentation: Option[Option[String]],
    scope: Option[Suggestion.Scope],
    reexport: Option[Option[String]]
  ): DBIO[(Long, Option[Long])] =
    for {
      idOpt <- updateSuggestionQuery(
        suggestion,
        externalId,
        returnType,
        documentation,
        scope,
        reexport
      )
      version <- currentVersionQuery
    } yield (version, idOpt)

  /** The query to update the suggestion.
    *
    * @param suggestion the key suggestion
    * @param externalId the external id to update
    * @param returnType the return type to update
    * @param documentation the documentation string to update
    * @param scope the scope to update
    */
  private def updateSuggestionQuery(
    suggestion: Suggestion,
    externalId: Option[Option[Suggestion.ExternalID]],
    returnType: Option[String],
    documentation: Option[Option[String]],
    scope: Option[Suggestion.Scope],
    reexport: Option[Option[String]]
  ): DBIO[Option[Long]] = {
    val raw   = toSuggestionRow(suggestion)
    val query = selectSuggestionQuery(raw)

    val updateQ = for {
      r1 <- DBIO.sequenceOption {
        externalId.map { extIdOpt =>
          query
            .map(r => (r.externalIdLeast, r.externalIdMost))
            .update(
              (
                extIdOpt.map(_.getLeastSignificantBits),
                extIdOpt.map(_.getMostSignificantBits)
              )
            )
        }
      }
      r2 <- DBIO.sequenceOption {
        returnType.map(tpe => query.map(_.returnType).update(tpe))
      }
      r3 <- DBIO.sequenceOption {
        documentation.map(doc => query.map(_.documentation).update(doc))
      }
      r4 <- DBIO.sequenceOption {
        scope.map { s =>
          query
            .map(r =>
              (
                r.scopeStartLine,
                r.scopeStartOffset,
                r.scopeEndLine,
                r.scopeEndOffset
              )
            )
            .update(
              (s.start.line, s.start.character, s.end.line, s.end.character)
            )
        }
      }
      r5 <- DBIO.sequenceOption {
        reexport.map { reexportOpt =>
          query.map(_.reexport).update(reexportOpt)
        }
      }
    } yield (r1 ++ r2 ++ r3 ++ r4 ++ r5).sum
    for {
      id <- query.map(_.id).result.headOption
      n  <- updateQ
      _  <- if (n > 0) incrementVersionQuery else DBIO.successful(())
    } yield id
  }

  /** The query to update a list of suggestions by external id.
    *
    * @param expressions the list of expressions to update
    * @return the current database version with the list of updated suggestion ids
    */
  private def updateAllQuery(
    expressions: Seq[(Suggestion.ExternalID, String)]
  ): DBIO[(Long, Seq[Option[Long]])] = {
    val query = for {
      ids <- DBIO.sequence(
        expressions.map(Function.tupled(updateByExternalIdQuery))
      )
      version <-
        if (ids.exists(_.nonEmpty)) incrementVersionQuery
        else currentVersionQuery
    } yield (version, ids)
    query
  }

  /** The query to get current version of the repo. */
  private def currentVersionQuery: DBIO[Long] = {
    for {
      versionOpt <- SuggestionsVersion.result.headOption
    } yield versionOpt.flatMap(_.id).getOrElse(0L)
  }

  /** The query to increment the current version of the repo. */
  private def incrementVersionQuery: DBIO[Long] = {
    for {
      version <- SuggestionsVersion.returning(
        SuggestionsVersion.map(_.id)
      ) += SuggestionsVersionRow(None)
      _ <- SuggestionsVersion.filterNot(_.id === version).delete
    } yield version
  }

  /** The query to get current version of the repo. */
  private def currentSchemaVersionQuery: DBIO[Long] = {
    for {
      versionOpt <- SchemaVersion.result.headOption
    } yield versionOpt.flatMap(_.id).getOrElse(0L)
  }

  /** The query to initialize the [[SchemaVersion]] table. */
  private def initSchemaVersionQuery: DBIO[Long] = {
    setSchemaVersionQuery(SchemaVersion.CurrentVersion)
  }

  /** The query setting the schema version.
    *
    * @param version the schema version.
    * @return the current value of the schema version
    */
  private def setSchemaVersionQuery(version: Long): DBIO[Long] = {
    val query = for {
      _ <- SchemaVersion.delete
      _ <- SchemaVersion += SchemaVersionRow(Some(version))
    } yield version
    query
  }

  /** The query to delete the schema version. */
  private def clearSchemaVersionQuery: DBIO[Unit] =
    for {
      _ <- SchemaVersion.delete
    } yield ()

  /** The query to insert suggestions in a batch.
    *
    * @param suggestions the list of suggestions to insert
    * @return the current size of the database
    */
  private def insertBatchJavaQuery(
    suggestions: Iterable[Suggestion]
  ): DBIO[Int] = {
    val rows = suggestions.map(toSuggestionRow)
    for {
      _    <- (Suggestions ++= rows).asTry
      size <- Suggestions.length.result
    } yield size
  }

  /** The query to insert suggestions in a batch.
    *
    * @param suggestions the list of suggestions to insert
    * @return the current size of the database
    */
  private def insertAllQuery(
    suggestions: Iterable[Suggestion]
  ): DBIO[Seq[Long]] = {
    val duplicatesBuilder = Vector.newBuilder[(Suggestion, Suggestion)]
    val suggestionsMap: mutable.Map[SuggestionRowUniqueIndex, Suggestion] =
      mutable.LinkedHashMap()
    suggestions.foreach { suggestion =>
      val idx = SuggestionRowUniqueIndex(suggestion)
      suggestionsMap.put(idx, suggestion).foreach { duplicate =>
        duplicatesBuilder.addOne((duplicate, suggestion))
      }
    }
    val duplicates = duplicatesBuilder.result()
    if (duplicates.isEmpty) {
      val rows = suggestions.map(toSuggestionRow)
      for {
        _    <- Suggestions ++= rows
        rows <- Suggestions.result
      } yield {
        val rowsMap =
          rows.map(r => SuggestionRowUniqueIndex(r) -> r.id.get).toMap
        suggestionsMap.keys.map(rowsMap(_)).toSeq
      }
    } else {
      DBIO.failed(SqlSuggestionsRepo.UniqueConstraintViolatedError(duplicates))
    }
  }

  private def insertAllWithVersionQuery(
    suggestions: Iterable[Suggestion]
  ): DBIO[(Long, Seq[Long])] = {
    for {
      ids     <- insertAllQuery(suggestions)
      version <- incrementVersionQuery
    } yield (version, ids)
  }

  private def selectAllSuggestionsQuery: DBIO[Seq[SuggestionEntry]] =
    for {
      rows <- Suggestions.result
    } yield {
      rows.map(row => SuggestionEntry(row.id.get, toSuggestion(row)))
    }

  /** Create a search query by the provided parameters.
    *
    * Even if the module is specified, the response includes all available
    * global symbols (atoms and method).
    *
    * @param module the module name search parameter
    * @param selfTypes the selfType search parameter
    * @param returnType the returnType search parameter
    * @param kinds the list suggestion kinds to search
    * @param position the absolute position in the text
    * @param isStatic the static attribute
    * @return the search query
    */
  private def searchQueryBuilder(
    module: Option[String],
    selfTypes: Seq[String],
    returnType: Option[String],
    kinds: Option[Seq[Suggestion.Kind]],
    position: Option[Suggestion.Position],
    isStatic: Option[Boolean]
  ): Query[SuggestionsTable, SuggestionRow, Seq] = {
    Suggestions
      .filterOpt(module) { case (row, value) =>
        row.scopeStartLine === ScopeColumn.EMPTY || row.module === value
      }
      .filterIf(selfTypes.isEmpty) { row =>
        row.kind =!= SuggestionKind.GETTER
      }
      .filterIf(selfTypes.nonEmpty) { row =>
        row.selfType.inSet(selfTypes) &&
        (row.kind =!= SuggestionKind.CONSTRUCTOR)
      }
      .filterOpt(returnType) { case (row, value) =>
        row.returnType === value
      }
      .filterOpt(kinds) { case (row, value) =>
        row.kind inSet value.map(SuggestionKind(_))
      }
      .filterOpt(position) { case (row, value) =>
        (row.scopeStartLine === ScopeColumn.EMPTY) ||
        (
          row.scopeStartLine <= value.line &&
          row.scopeEndLine >= value.line
        )
      }
      .filterOpt(isStatic) { case (row, value) =>
        (row.kind === SuggestionKind.METHOD && row.isStatic === value) ||
        (row.kind =!= SuggestionKind.METHOD)
      }
  }

  /** Convert the suggestion to a row in the suggestions table. */
  private def toSuggestionRow(suggestion: Suggestion): SuggestionRow =
    suggestion match {
      case Suggestion.Module(module, doc, reexport) =>
        SuggestionRow(
          id               = None,
          externalIdLeast  = None,
          externalIdMost   = None,
          kind             = SuggestionKind.MODULE,
          module           = module,
          name             = module,
          selfType         = SelfTypeColumn.EMPTY,
          returnType       = "",
          parentType       = None,
          isStatic         = false,
          scopeStartLine   = ScopeColumn.EMPTY,
          scopeStartOffset = ScopeColumn.EMPTY,
          scopeEndLine     = ScopeColumn.EMPTY,
          scopeEndOffset   = ScopeColumn.EMPTY,
          documentation    = doc,
          reexport         = reexport
        )
      case Suggestion.Type(
            expr,
            module,
            name,
            _,
            returnType,
            parentType,
            doc,
            reexport
          ) =>
        SuggestionRow(
          id               = None,
          externalIdLeast  = expr.map(_.getLeastSignificantBits),
          externalIdMost   = expr.map(_.getMostSignificantBits),
          kind             = SuggestionKind.TYPE,
          module           = module,
          name             = name,
          selfType         = SelfTypeColumn.EMPTY,
          returnType       = returnType,
          parentType       = parentType,
          isStatic         = false,
          documentation    = doc,
          scopeStartLine   = ScopeColumn.EMPTY,
          scopeStartOffset = ScopeColumn.EMPTY,
          scopeEndLine     = ScopeColumn.EMPTY,
          scopeEndOffset   = ScopeColumn.EMPTY,
          reexport         = reexport
        )
      case Suggestion.Constructor(
            expr,
            module,
            name,
            _,
            returnType,
            doc,
            _,
            reexport
          ) =>
        SuggestionRow(
          id               = None,
          externalIdLeast  = expr.map(_.getLeastSignificantBits),
          externalIdMost   = expr.map(_.getMostSignificantBits),
          kind             = SuggestionKind.CONSTRUCTOR,
          module           = module,
          name             = name,
          selfType         = returnType,
          returnType       = returnType,
          parentType       = None,
          isStatic         = false,
          documentation    = doc,
          scopeStartLine   = ScopeColumn.EMPTY,
          scopeStartOffset = ScopeColumn.EMPTY,
          scopeEndLine     = ScopeColumn.EMPTY,
          scopeEndOffset   = ScopeColumn.EMPTY,
          reexport         = reexport
        )
      case Suggestion.Getter(
            expr,
            module,
            name,
            _,
            selfType,
            returnType,
            doc,
            _,
            reexport
          ) =>
        SuggestionRow(
          id               = None,
          externalIdLeast  = expr.map(_.getLeastSignificantBits),
          externalIdMost   = expr.map(_.getMostSignificantBits),
          kind             = SuggestionKind.GETTER,
          module           = module,
          name             = name,
          selfType         = selfType,
          returnType       = returnType,
          parentType       = None,
          isStatic         = false,
          documentation    = doc,
          scopeStartLine   = ScopeColumn.EMPTY,
          scopeStartOffset = ScopeColumn.EMPTY,
          scopeEndLine     = ScopeColumn.EMPTY,
          scopeEndOffset   = ScopeColumn.EMPTY,
          reexport         = reexport
        )
      case Suggestion.DefinedMethod(
            expr,
            module,
            name,
            _,
            selfType,
            returnType,
            isStatic,
            doc,
            _,
            reexport
          ) =>
        SuggestionRow(
          id               = None,
          externalIdLeast  = expr.map(_.getLeastSignificantBits),
          externalIdMost   = expr.map(_.getMostSignificantBits),
          kind             = SuggestionKind.METHOD,
          module           = module,
          name             = name,
          selfType         = selfType,
          returnType       = returnType,
          parentType       = None,
          isStatic         = isStatic,
          documentation    = doc,
          scopeStartLine   = ScopeColumn.EMPTY,
          scopeStartOffset = ScopeColumn.EMPTY,
          scopeEndLine     = ScopeColumn.EMPTY,
          scopeEndOffset   = ScopeColumn.EMPTY,
          reexport         = reexport
        )
      case Suggestion.Conversion(
            expr,
            module,
            _,
            sourceType,
            returnType,
            doc,
            reexport
          ) =>
        SuggestionRow(
          id               = None,
          externalIdLeast  = expr.map(_.getLeastSignificantBits),
          externalIdMost   = expr.map(_.getMostSignificantBits),
          kind             = SuggestionKind.CONVERSION,
          module           = module,
          name             = NameColumn.conversionMethodName(sourceType, returnType),
          selfType         = sourceType,
          returnType       = returnType,
          parentType       = None,
          isStatic         = false,
          documentation    = doc,
          scopeStartLine   = ScopeColumn.EMPTY,
          scopeStartOffset = ScopeColumn.EMPTY,
          scopeEndLine     = ScopeColumn.EMPTY,
          scopeEndOffset   = ScopeColumn.EMPTY,
          reexport         = reexport
        )
      case Suggestion.Function(
            expr,
            module,
            name,
            _,
            returnType,
            scope,
            doc
          ) =>
        SuggestionRow(
          id               = None,
          externalIdLeast  = expr.map(_.getLeastSignificantBits),
          externalIdMost   = expr.map(_.getMostSignificantBits),
          kind             = SuggestionKind.FUNCTION,
          module           = module,
          name             = name,
          selfType         = SelfTypeColumn.EMPTY,
          returnType       = returnType,
          parentType       = None,
          isStatic         = false,
          documentation    = doc,
          scopeStartLine   = scope.start.line,
          scopeStartOffset = scope.start.character,
          scopeEndLine     = scope.end.line,
          scopeEndOffset   = scope.end.character,
          reexport         = None
        )
      case Suggestion.Local(expr, module, name, returnType, scope, doc) =>
        SuggestionRow(
          id               = None,
          externalIdLeast  = expr.map(_.getLeastSignificantBits),
          externalIdMost   = expr.map(_.getMostSignificantBits),
          kind             = SuggestionKind.LOCAL,
          module           = module,
          name             = name,
          selfType         = SelfTypeColumn.EMPTY,
          returnType       = returnType,
          parentType       = None,
          isStatic         = false,
          documentation    = doc,
          scopeStartLine   = scope.start.line,
          scopeStartOffset = scope.start.character,
          scopeEndLine     = scope.end.line,
          scopeEndOffset   = scope.end.character,
          reexport         = None
        )
    }

  /** Convert the database rows to a suggestion entry. */
  private def toSuggestionEntry(suggestion: SuggestionRow): SuggestionEntry =
    SuggestionEntry(suggestion.id.get, toSuggestion(suggestion))

  /** Convert the database rows to a suggestion. */
  private def toSuggestion(suggestion: SuggestionRow): Suggestion =
    suggestion.kind match {
      case SuggestionKind.MODULE =>
        Suggestion.Module(
          module        = suggestion.module,
          documentation = suggestion.documentation,
          reexport      = suggestion.reexport
        )
      case SuggestionKind.TYPE =>
        Suggestion.Type(
          externalId =
            toUUID(suggestion.externalIdLeast, suggestion.externalIdMost),
          module        = suggestion.module,
          name          = suggestion.name,
          params        = Seq(),
          returnType    = suggestion.returnType,
          parentType    = suggestion.parentType,
          documentation = suggestion.documentation,
          reexport      = suggestion.reexport
        )
      case SuggestionKind.CONSTRUCTOR =>
        Suggestion.Constructor(
          externalId =
            toUUID(suggestion.externalIdLeast, suggestion.externalIdMost),
          module        = suggestion.module,
          name          = suggestion.name,
          arguments     = Seq(),
          returnType    = suggestion.returnType,
          documentation = suggestion.documentation,
          annotations   = Seq(),
          reexport      = suggestion.reexport
        )
      case SuggestionKind.GETTER =>
        Suggestion.Getter(
          externalId =
            toUUID(suggestion.externalIdLeast, suggestion.externalIdMost),
          module        = suggestion.module,
          name          = suggestion.name,
          arguments     = Seq(),
          selfType      = suggestion.selfType,
          returnType    = suggestion.returnType,
          documentation = suggestion.documentation,
          annotations   = Seq(),
          reexport      = suggestion.reexport
        )
      case SuggestionKind.METHOD =>
        Suggestion.DefinedMethod(
          externalId =
            toUUID(suggestion.externalIdLeast, suggestion.externalIdMost),
          module        = suggestion.module,
          name          = suggestion.name,
          arguments     = Seq(),
          selfType      = suggestion.selfType,
          returnType    = suggestion.returnType,
          isStatic      = suggestion.isStatic,
          documentation = suggestion.documentation,
          annotations   = Seq(),
          reexport      = suggestion.reexport
        )
      case SuggestionKind.CONVERSION =>
        Suggestion.Conversion(
          externalId =
            toUUID(suggestion.externalIdLeast, suggestion.externalIdMost),
          module        = suggestion.module,
          arguments     = Seq(),
          selfType      = suggestion.selfType,
          returnType    = suggestion.returnType,
          documentation = suggestion.documentation,
          reexport      = suggestion.reexport
        )
      case SuggestionKind.FUNCTION =>
        Suggestion.Function(
          externalId =
            toUUID(suggestion.externalIdLeast, suggestion.externalIdMost),
          module     = suggestion.module,
          name       = suggestion.name,
          arguments  = Seq(),
          returnType = suggestion.returnType,
          scope = Suggestion.Scope(
            Suggestion.Position(
              suggestion.scopeStartLine,
              suggestion.scopeStartOffset
            ),
            Suggestion.Position(
              suggestion.scopeEndLine,
              suggestion.scopeEndOffset
            )
          ),
          documentation = suggestion.documentation
        )
      case SuggestionKind.LOCAL =>
        Suggestion.Local(
          externalId =
            toUUID(suggestion.externalIdLeast, suggestion.externalIdMost),
          module     = suggestion.module,
          name       = suggestion.name,
          returnType = suggestion.returnType,
          scope = Suggestion.Scope(
            Suggestion.Position(
              suggestion.scopeStartLine,
              suggestion.scopeStartOffset
            ),
            Suggestion.Position(
              suggestion.scopeEndLine,
              suggestion.scopeEndOffset
            )
          ),
          documentation = suggestion.documentation
        )
      case k =>
        throw new NoSuchElementException(s"Unknown suggestion kind: $k")
    }

  /** Convert bits to the UUID.
    *
    * @param least the least significant bits of the UUID
    * @param most the most significant bits of the UUID
    * @return the new UUID
    */
  private def toUUID(least: Option[Long], most: Option[Long]): Option[UUID] =
    for {
      l <- least
      m <- most
    } yield new UUID(m, l)

}

object SqlSuggestionsRepo {

  /** An error indicating that the database unique constraint was violated.
    *
    * @param duplicates the entries that violate the unique constraint
    */
  final case class UniqueConstraintViolatedError(
    duplicates: Seq[(Suggestion, Suggestion)]
  ) extends Exception(s"Database unique constraint is violated [$duplicates].")
}
